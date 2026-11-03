package net.tfminecraft.denareconomy.ledger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;

import net.tfminecraft.denareconomy.api.Leg;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.denareconomy.api.TaxLeg;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.tlibs.database.SqliteDatabase;

/**
 * Every balance and every posting, in one SQLite database. Each posting is one transaction and its
 * operation ID is kept forever, so a retry never applies twice. Main thread only.
 *
 * <p>After any SQLite error the ledger closes and refuses everything until the plugin restarts.
 * It never falls back to memory.
 *
 * <p>Durability: the write-ahead log runs with {@code synchronous=FULL}, so a posting that answers
 * {@link Result#APPLIED} is on disk. Other plugins rely on that: they keep their own journals and
 * act on an applied posting. The one exception is a posting made with {@code relaxed} (only
 * DenarEconomy's own coin pickups, most postings by count): it is committed without its own sync.
 * A crash or kill of the server process still loses nothing, because the commit already reached
 * the operating system. The next fully synced commit, or the checkpoint the plugin runs every
 * {@link #CHECKPOINT_TICKS} ticks (SQLite syncs the log before copying it), puts it on disk, so an
 * operating-system crash or power loss can only roll back relaxed postings from the last few
 * seconds (about ten at full speed). The database never corrupts and an operation ID never
 * applies twice.
 */
public final class Ledger {
	/** SQLite primary result codes a checkpoint may retry after. */
	static final int SQLITE_BUSY = 5;
	static final int SQLITE_LOCKED = 6;

	private static final String[] SCHEMA = {
		"PRAGMA journal_mode=WAL",
		"PRAGMA synchronous=FULL",
		"CREATE TABLE IF NOT EXISTS accounts(id TEXT PRIMARY KEY, kind TEXT NOT NULL, owner TEXT,"
				+ " character TEXT, label TEXT, cents INTEGER NOT NULL)",
		"CREATE TABLE IF NOT EXISTS operations(op_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL,"
				+ " status TEXT NOT NULL, created INTEGER NOT NULL, reason TEXT NOT NULL)",
		"CREATE TABLE IF NOT EXISTS legs(op_id TEXT NOT NULL, account TEXT NOT NULL, cents INTEGER NOT NULL)",
		"CREATE INDEX IF NOT EXISTS legs_by_account ON legs(account)",
		// The estate sweep sums each estate operation's legs by op_id every hour. Without this
		// index that subquery scanned every leg ever posted (~0.4 s on Main's 117 MB ledger).
		"CREATE INDEX IF NOT EXISTS legs_by_op ON legs(op_id)",
		"CREATE TABLE IF NOT EXISTS tax_earnings(op_id TEXT PRIMARY KEY, account TEXT NOT NULL,"
				+ " character TEXT NOT NULL, cents INTEGER NOT NULL)"
	};

	/** One stored account. */
	public record Row(String id, String kind, String owner, String character, String label, long cents) {
	}

	/** One applied operation, with its net change per requested account. */
	public record Posting(String opId, String reason, long created, Map<String, Long> legs) {
	}

	/**
	 * What one estate sweep did. {@code opId} is empty when nothing was recorded: the character
	 * was not DEAD with that death, both balances were zero, the total would overflow or the
	 * ledger or identity was unavailable. {@code cents} is what an applied sweep moved.
	 */
	public record Sweep(Result result, Optional<String> opId, long cents) {
		static final Sweep UNAVAILABLE = new Sweep(Result.UNAVAILABLE, Optional.empty(), 0);
		static final Sweep REFUSED = new Sweep(Result.REFUSED, Optional.empty(), 0);
		static final Sweep EMPTY = new Sweep(Result.APPLIED, Optional.empty(), 0);
	}

	/** One recorded estate operation and the cents it took from the dead character. */
	public record EstatePosting(String opId, long number, Result result, long cents) {
	}

	private record Prepared(Map<String, Long> changes, String fingerprint) {
	}

	private static final String ESTATE = "estate:";

	private interface Work<T> {
		T run(Connection connection) throws SQLException;
	}

	/** Ticks between checkpoints: about ten seconds, the most an OS crash or power loss can roll back. */
	public static final long CHECKPOINT_TICKS = 20L * 10;

	private final Logger log;
	private final Predicate<String> frozen;
	private SqliteDatabase database;
	private boolean checkpointFailing;

	private Ledger(Logger log, Predicate<String> frozen) {
		this.log = log;
		this.frozen = frozen;
	}

	/**
	 * Opens or creates the ledger. If it cannot be opened the error is logged and the ledger is
	 * returned closed.
	 *
	 * @param frozen accounts whose debits are refused unless the reason starts with
	 *               {@code staff:} or {@code estate:}
	 */
	public static Ledger open(File file, Logger log, Predicate<String> frozen) {
		requireMainThread();
		Ledger ledger = new Ledger(log, frozen);
		try {
			ledger.database = new SqliteDatabase(file);
		} catch (RuntimeException e) {
			log.log(Level.SEVERE, "Could not open the ledger " + file + "; money is unavailable", e);
			return ledger;
		}
		ledger.run("setup", null, connection -> {
			try (Statement statement = connection.createStatement()) {
				for (String sql : SCHEMA) {
					statement.execute(sql);
				}
			}
			return null;
		});
		return ledger;
	}

	/** A ledger that refuses everything, for use before the plugin enables. */
	public static Ledger unavailable() {
		return new Ledger(Logger.getLogger("DenarEconomy"), AccountIds::frozen);
	}

	public boolean isOpen() {
		return database != null;
	}

	/**
	 * Applies every leg or none. Legs on the same account are added together first. An operation ID
	 * or reason starting {@code estate:} is reserved for {@link #sweepEstate} and answers
	 * {@link Result#UNAVAILABLE} without recording anything.
	 *
	 * @throws IllegalArgumentException if an ID is blank, an account ID is malformed or the legs
	 *                                  do not sum to zero
	 */
	public Result post(String opId, String reason, List<Leg> legs) {
		return postEarning(opId, reason, legs, null, null);
	}

	/** Posts an earning and its guild tax attribution in one transaction. Tax must match its net credit. */
	public Result postEarning(String opId, String reason, List<Leg> legs, UUID earner, TaxLeg tax) {
		return postEarning(opId, reason, legs, earner, tax, false);
	}

	/**
	 * {@link #postEarning(String, String, List, UUID, TaxLeg)}; when {@code relaxed}, the commit is
	 * not synced on its own and reaches the disk with the next synced commit or checkpoint (see the
	 * class notes). Only for postings nothing outside this plugin acts on, like coin pickups.
	 */
	public Result postEarning(String opId, String reason, List<Leg> legs, UUID earner, TaxLeg tax, boolean relaxed) {
		requireText(opId, "operation ID");
		requireText(reason, "reason");
		if (reserved(opId) || reserved(reason)) {
			log.warning("Refused posting " + opId + ": estate operations are posted only by estate sweeps");
			return Result.UNAVAILABLE;
		}
		Prepared prepared = prepare(opId, reason, legs);
		if (tax != null && tax.cents() > 0 && AccountIds.parse(tax.account()).kind().equals("guildtax")) {
			if (earner == null || prepared.changes() == null
					|| prepared.changes().getOrDefault(tax.account(), 0L) != tax.cents()) {
				throw new IllegalArgumentException("Guild tax attribution must match the earning character and posting credit");
			}
		}
		return run("posting " + opId, Result.UNAVAILABLE, connection -> {
			if (!relaxed) {
				return transaction(connection, opId, reason, prepared, earner, tax);
			}
			setSynchronous(connection, "NORMAL");
			Result result;
			try {
				result = transaction(connection, opId, reason, prepared, earner, tax);
			} catch (SQLException | RuntimeException | Error e) {
				// The level can only change outside a transaction: end the failed one first, so the
				// original failure is the one reported (and an SQLException still closes the ledger).
				try {
					if (!connection.getAutoCommit()) {
						connection.rollback();
						connection.setAutoCommit(true);
					}
					setSynchronous(connection, "FULL");
				} catch (SQLException restore) {
					e.addSuppressed(restore);
				}
				throw e;
			}
			// Back to synced commits before anything else posts. A failure here closes the ledger.
			setSynchronous(connection, "FULL");
			return result;
		});
	}

	private Result transaction(Connection connection, String opId, String reason, Prepared prepared, UUID earner,
			TaxLeg tax) throws SQLException {
		connection.setAutoCommit(false);
		Result result = apply(connection, opId, reason, prepared.fingerprint(), prepared.changes(), earner, tax);
		connection.commit();
		connection.setAutoCommit(true);
		return result;
	}

	private static void setSynchronous(Connection connection, String level) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA synchronous=" + level);
		}
	}

	/** Whether this operation ID or reason belongs to estate sweeps. */
	public static boolean reserved(String text) {
		return text != null && text.startsWith(ESTATE);
	}

	/**
	 * Moves everything in a dead character's pouch and bank to the estate's money recipient, as
	 * operation {@code estate:<deathId>:<n>}. Before anything is read, the character must be DEAD
	 * with this {@code deathId}; otherwise nothing is recorded. In one transaction the sweep takes
	 * {@code n} one above the highest number already used for this death, whatever that
	 * operation's status, so a number is never reused and a sweep cannot conflict. Zero balances
	 * post nothing.
	 *
	 * <p>Package-private: only {@link MoneyEstates} sweeps, after the estate guard.
	 *
	 * @throws IllegalArgumentException if the recipient is not a ledger account
	 */
	Sweep sweepEstate(UUID deathId, UUID characterId, String recipientAccount) {
		requireMainThread();
		AccountIds.parse(recipientAccount);
		if (!Characters.available()) {
			return Sweep.UNAVAILABLE;
		}
		boolean dead = Characters.info(characterId).map(info -> info.status() == CharacterStatus.DEAD).orElse(false);
		if (!dead || !Characters.deathId(characterId).equals(Optional.of(deathId))) {
			return Sweep.REFUSED;
		}
		String pouch = AccountIds.pouch(characterId);
		String bank = AccountIds.bank(characterId);
		return run("sweeping estate " + deathId, Sweep.UNAVAILABLE, connection -> {
			connection.setAutoCommit(false);
			long last = estatePostings(connection, deathId).stream().mapToLong(EstatePosting::number).max().orElse(0);
			long inPouch = balance(connection, pouch);
			long inBank = balance(connection, bank);
			Sweep sweep;
			if (inPouch <= 0 && inBank <= 0) {
				sweep = Sweep.EMPTY;
			} else if (inPouch > Long.MAX_VALUE - inBank) {
				sweep = Sweep.REFUSED;
			} else {
				String opId = ESTATE + deathId + ":" + (last + 1);
				long total = inPouch + inBank;
				// Both debits always, so a zero leg still counts in the fingerprint.
				Prepared prepared = prepare(opId, ESTATE + deathId, List.of(new Leg(pouch, -inPouch),
						new Leg(bank, -inBank), new Leg(recipientAccount, total)));
				Result result = apply(connection, opId, ESTATE + deathId, prepared.fingerprint(), prepared.changes(), null, null);
				sweep = new Sweep(result, Optional.of(opId), result == Result.APPLIED ? total : 0);
			}
			connection.commit();
			connection.setAutoCommit(true);
			return sweep;
		});
	}

	/**
	 * Every estate operation recorded for this death, in number order, with the cents it took from
	 * the dead character. Empty when the ledger is closed.
	 */
	public List<EstatePosting> estatePostings(UUID deathId) {
		return run("reading estate " + deathId, List.of(), connection -> List.copyOf(estatePostings(connection, deathId)));
	}

	/** The stored result of an operation, or empty if it was never posted or the ledger is closed. */
	public Optional<Result> status(String opId) {
		return run("reading " + opId, Optional.empty(), connection -> {
			String[] stored = stored(connection, opId);
			return stored == null ? Optional.empty() : Optional.of(Result.valueOf(stored[1]));
		});
	}

	/** The balance in cents; zero for an account never posted to or a closed ledger. */
	public long balance(String account) {
		AccountIds.parse(account);
		return run("reading " + account, 0L, connection -> balance(connection, account));
	}

	/**
	 * Guild tax credited per character since the holding account's latest debit, in cents. A debit
	 * ends the attribution period; any residual or older unattributed money remains in the balance.
	 */
	public Map<UUID, Long> taxContributors(UUID incarnation) {
		String account = AccountIds.guildTax(incarnation);
		return run("reading tax contributors for " + account, Map.of(), connection -> {
			String sql = "SELECT character, SUM(cents) FROM tax_earnings WHERE account = ? AND op_id IN"
					+ " (SELECT op_id FROM legs WHERE account = ? AND cents > 0 AND rowid > COALESCE("
					+ " (SELECT MAX(rowid) FROM legs WHERE account = ? AND cents < 0), 0)) GROUP BY character";
			try (PreparedStatement query = connection.prepareStatement(sql)) {
				for (int index = 1; index <= 3; index++) query.setString(index, account);
				Map<UUID, Long> amounts = new LinkedHashMap<>();
				try (ResultSet row = query.executeQuery()) {
					while (row.next()) amounts.put(UUID.fromString(row.getString(1)), row.getLong(2));
				}
				return Map.copyOf(amounts);
			}
		});
	}

	/** Every account of one kind ({@code char}, {@code legacy}, {@code guild} ...), by ID. */
	public List<Row> accounts(String kind) {
		return run("listing " + kind + " accounts", List.of(), connection -> {
			try (PreparedStatement query = connection.prepareStatement(
					"SELECT id, kind, owner, character, label, cents FROM accounts WHERE kind = ? ORDER BY id")) {
				query.setString(1, kind);
				ResultSet row = query.executeQuery();
				List<Row> rows = new ArrayList<>();
				while (row.next()) {
					rows.add(new Row(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
							row.getString(5), row.getLong(6)));
				}
				return rows;
			}
		});
	}

	/** The latest applied operations touching any of these accounts, newest first. */
	public List<Posting> history(List<String> accounts, int limit) {
		String in = String.join(",", Collections.nCopies(accounts.size(), "?"));
		String sql = "SELECT o.op_id, o.reason, o.created, l.account, l.cents FROM legs l"
				+ " JOIN operations o ON o.op_id = l.op_id WHERE l.account IN (" + in + ") AND l.op_id IN"
				+ " (SELECT op_id FROM legs WHERE account IN (" + in + ") GROUP BY op_id"
				+ " ORDER BY MAX(rowid) DESC LIMIT ?) ORDER BY l.rowid DESC";
		return run("reading history", List.of(), connection -> {
			try (PreparedStatement query = connection.prepareStatement(sql)) {
				int index = 1;
				for (int pass = 0; pass < 2; pass++) {
					for (String account : accounts) {
						query.setString(index++, account);
					}
				}
				query.setInt(index, limit);
				ResultSet row = query.executeQuery();
				Map<String, Posting> postings = new LinkedHashMap<>();
				while (row.next()) {
					Posting posting = postings.get(row.getString(1));
					if (posting == null) {
						posting = new Posting(row.getString(1), row.getString(2), row.getLong(3), new LinkedHashMap<>());
						postings.put(posting.opId(), posting);
					}
					posting.legs().put(row.getString(4), row.getLong(5));
				}
				return List.copyOf(postings.values());
			}
		});
	}

	/**
	 * Copies committed postings from the write-ahead log into the database file. SQLite syncs the
	 * log before the copy, so every posting committed before this call is then on disk.
	 *
	 * <p>A checkpoint the database is too busy for is only logged, once until one works again: every
	 * committed posting stays in the log, readable, and the next checkpoint retries. Any other
	 * failure (a disk or I/O error) closes the ledger like every other SQLite error, since relaxed
	 * postings would otherwise keep answering applied while nothing reaches the disk.
	 *
	 * @return whether the checkpoint ran; false when closed or when it failed
	 */
	public boolean checkpoint() {
		requireMainThread();
		if (database == null) {
			return false;
		}
		try (Statement statement = database.getConnection().createStatement();
				ResultSet row = statement.executeQuery("PRAGMA wal_checkpoint(PASSIVE)")) {
			// Columns: busy, log frames, checkpointed frames. A PASSIVE checkpoint reports a busy
			// database here instead of throwing; nothing was synced then, so it counts as a retry.
			if (row.next() && row.getInt(1) != 0) {
				throw new SQLException("The ledger database is busy", null, SQLITE_BUSY);
			}
		} catch (SQLException e) {
			int code = e.getErrorCode() & 0xff;
			if (code != SQLITE_BUSY && code != SQLITE_LOCKED) {
				log.log(Level.SEVERE, "Ledger checkpoint failed; refusing all postings until DenarEconomy restarts", e);
				close();
				return false;
			}
			if (!checkpointFailing) {
				log.log(Level.WARNING, "Ledger checkpoint failed; committed postings stay in the write-ahead log"
						+ " and the checkpoint is retried", e);
			}
			checkpointFailing = true;
			return false;
		}
		if (checkpointFailing) {
			log.info("Ledger checkpoints work again");
			checkpointFailing = false;
		}
		return true;
	}

	/** The connection's {@code PRAGMA synchronous} level (1 = NORMAL), or -1 when closed. For tests. */
	int synchronousLevel() {
		return run("reading the sync level", -1, connection -> {
			try (Statement statement = connection.createStatement();
					ResultSet row = statement.executeQuery("PRAGMA synchronous")) {
				row.next();
				return row.getInt(1);
			}
		});
	}

	/** Closes the database. Safe to call again. */
	public void close() {
		requireMainThread();
		SqliteDatabase open = database;
		database = null;
		if (open != null) {
			open.close();
		}
	}

	/** Records the outcome. {@code changes} is null when summing the legs overflowed. */
	private Result apply(Connection connection, String opId, String reason, String fingerprint,
			Map<String, Long> changes, UUID earner, TaxLeg tax) throws SQLException {
		String[] stored = stored(connection, opId);
		if (stored != null) {
			if (stored[0].equals(fingerprint) && sameTaxAttribution(connection, opId, earner, tax)) {
				return Result.valueOf(stored[1]);
			}
			log.warning("Operation " + opId + " was posted again with different legs or tax attribution; its first result stands");
			return Result.CONFLICT;
		}
		Map<String, Long> after = changes == null ? null : balancesAfter(connection, reason, changes);
		Result result = after == null ? Result.REFUSED : Result.APPLIED;
		try (PreparedStatement insert = connection.prepareStatement(
				"INSERT INTO operations(op_id, fingerprint, status, created, reason) VALUES (?, ?, ?, ?, ?)")) {
			insert.setString(1, opId);
			insert.setString(2, fingerprint);
			insert.setString(3, result.name());
			insert.setLong(4, System.currentTimeMillis());
			insert.setString(5, reason);
			insert.executeUpdate();
		}
		if (after == null) {
			return result;
		}
		try (PreparedStatement account = connection.prepareStatement(
				"INSERT INTO accounts(id, kind, owner, character, label, cents) VALUES (?, ?, ?, ?, ?, ?)"
						+ " ON CONFLICT(id) DO UPDATE SET cents = excluded.cents");
				PreparedStatement leg = connection.prepareStatement(
						"INSERT INTO legs(op_id, account, cents) VALUES (?, ?, ?)")) {
			for (Map.Entry<String, Long> entry : after.entrySet()) {
				AccountIds.Parts parts = AccountIds.parse(entry.getKey());
				account.setString(1, entry.getKey());
				account.setString(2, parts.kind());
				account.setString(3, parts.owner());
				account.setString(4, parts.character());
				account.setString(5, parts.label());
				account.setLong(6, entry.getValue());
				account.executeUpdate();
				leg.setString(1, opId);
				leg.setString(2, entry.getKey());
				leg.setLong(3, changes.get(entry.getKey()));
				leg.executeUpdate();
			}
		}
		if (earner != null && tax != null && tax.cents() > 0
				&& AccountIds.parse(tax.account()).kind().equals("guildtax")) {
			try (PreparedStatement insert = connection.prepareStatement(
					"INSERT INTO tax_earnings(op_id, account, character, cents) VALUES (?, ?, ?, ?)")) {
				insert.setString(1, opId);
				insert.setString(2, tax.account());
				insert.setString(3, earner.toString());
				insert.setLong(4, tax.cents());
				insert.executeUpdate();
			}
		}
		return result;
	}

	/** The new balance of every changed account, or null when the posting must be refused. */
	private Map<String, Long> balancesAfter(Connection connection, String reason, Map<String, Long> changes)
			throws SQLException {
		boolean exempt = reason.startsWith("staff:") || reason.startsWith("estate:");
		Map<String, Long> after = new TreeMap<>();
		for (Map.Entry<String, Long> change : changes.entrySet()) {
			String account = change.getKey();
			long delta = change.getValue();
			if (delta == 0) {
				continue;
			}
			long next;
			try {
				next = Math.addExact(balance(connection, account), delta);
			} catch (ArithmeticException e) {
				return null;
			}
			if (next < 0 && !AccountIds.mayGoNegative(account) || delta < 0 && !exempt && frozen.test(account)) {
				return null;
			}
			after.put(account, next);
		}
		return after;
	}

	/**
	 * Nets the legs per account and fingerprints the posting. The changes are null when a sum
	 * overflows, which refuses the posting.
	 */
	private static Prepared prepare(String opId, String reason, List<Leg> legs) {
		if (legs.isEmpty()) {
			throw new IllegalArgumentException("A posting needs legs");
		}
		Map<String, Long> net = new TreeMap<>();
		List<String> lines = new ArrayList<>();
		long sum = 0;
		boolean overflow = false;
		for (Leg leg : legs) {
			AccountIds.parse(leg.account());
			lines.add(leg.account() + "=" + leg.cents());
			try {
				sum = Math.addExact(sum, leg.cents());
				net.merge(leg.account(), leg.cents(), Math::addExact);
			} catch (ArithmeticException e) {
				overflow = true;
			}
		}
		if (!overflow && sum != 0) {
			throw new IllegalArgumentException("Legs of " + opId + " sum to " + sum + ", not zero");
		}
		Collections.sort(lines);
		String fingerprint = UUID.nameUUIDFromBytes(
				(reason + "\n" + String.join("\n", lines)).getBytes(StandardCharsets.UTF_8)).toString();
		return new Prepared(overflow ? null : net, fingerprint);
	}

	/** Every operation numbered under {@code estate:<deathId>:}, whatever its status, by number. */
	private static List<EstatePosting> estatePostings(Connection connection, UUID deathId) throws SQLException {
		String prefix = ESTATE + deathId + ":";
		List<EstatePosting> postings = new ArrayList<>();
		try (PreparedStatement query = connection.prepareStatement(
				"SELECT o.op_id, o.status, COALESCE((SELECT -SUM(l.cents) FROM legs l WHERE l.op_id = o.op_id"
						+ " AND l.cents < 0), 0) FROM operations o WHERE o.op_id >= ? AND o.op_id < ?")) {
			query.setString(1, prefix);
			// ';' follows ':', so this range is every ID starting with the prefix.
			query.setString(2, ESTATE + deathId + ";");
			ResultSet row = query.executeQuery();
			while (row.next()) {
				String suffix = row.getString(1).substring(prefix.length());
				// Numbers only: an older jar may have stored other IDs under this prefix.
				if (suffix.matches("[0-9]{1,18}")) {
					postings.add(new EstatePosting(row.getString(1), Long.parseLong(suffix),
							Result.valueOf(row.getString(2)), row.getLong(3)));
				}
			}
		}
		postings.sort(Comparator.comparingLong(EstatePosting::number));
		return postings;
	}

	/** The stored fingerprint and status of an operation, or null. */
	private static String[] stored(Connection connection, String opId) throws SQLException {
		try (PreparedStatement query = connection.prepareStatement(
				"SELECT fingerprint, status FROM operations WHERE op_id = ?")) {
			query.setString(1, opId);
			ResultSet row = query.executeQuery();
			return row.next() ? new String[] {row.getString(1), row.getString(2)} : null;
		}
	}

	private static boolean sameTaxAttribution(Connection connection, String opId, UUID earner, TaxLeg tax) throws SQLException {
		try (PreparedStatement query = connection.prepareStatement("SELECT character, account FROM tax_earnings WHERE op_id = ?")) {
			query.setString(1, opId);
			try (ResultSet row = query.executeQuery()) {
				// Older postings have no attribution; preserve their existing retry result.
				return !row.next() || earner != null && earner.toString().equals(row.getString(1))
						&& tax != null && tax.account().equals(row.getString(2));
			}
		}
	}

	private static long balance(Connection connection, String account) throws SQLException {
		try (PreparedStatement query = connection.prepareStatement("SELECT cents FROM accounts WHERE id = ?")) {
			query.setString(1, account);
			ResultSet row = query.executeQuery();
			return row.next() ? row.getLong(1) : 0L;
		}
	}

	private <T> T run(String what, T unavailable, Work<T> work) {
		requireMainThread();
		if (database == null) {
			return unavailable;
		}
		try {
			return work.run(database.getConnection());
		} catch (SQLException e) {
			log.log(Level.SEVERE, "Ledger " + what + " failed; refusing all postings until DenarEconomy restarts", e);
			close();
			return unavailable;
		}
	}

	private static void requireText(String value, String what) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("A posting needs a " + what);
		}
	}

	/** @throws IllegalStateException off the server's main thread */
	public static void requireMainThread() {
		if (!Bukkit.isPrimaryThread()) {
			throw new IllegalStateException("The DenarEconomy ledger must be used on the main thread");
		}
	}
}
