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
import net.tfminecraft.tlibs.database.SqliteDatabase;

/**
 * Every balance and every posting, in one SQLite database. Each posting is one transaction and its
 * operation ID is kept forever, so a retry never applies twice. Main thread only.
 *
 * <p>After any SQLite error the ledger closes and refuses everything until the plugin restarts.
 * It never falls back to memory.
 */
public final class Ledger {
	private static final String[] SCHEMA = {
		"PRAGMA journal_mode=WAL",
		"PRAGMA synchronous=FULL",
		"CREATE TABLE IF NOT EXISTS accounts(id TEXT PRIMARY KEY, kind TEXT NOT NULL, owner TEXT,"
				+ " character TEXT, label TEXT, cents INTEGER NOT NULL)",
		"CREATE TABLE IF NOT EXISTS operations(op_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL,"
				+ " status TEXT NOT NULL, created INTEGER NOT NULL, reason TEXT NOT NULL)",
		"CREATE TABLE IF NOT EXISTS legs(op_id TEXT NOT NULL, account TEXT NOT NULL, cents INTEGER NOT NULL)",
		"CREATE INDEX IF NOT EXISTS legs_by_account ON legs(account)"
	};

	/** One stored account. */
	public record Row(String id, String kind, String owner, String character, String label, long cents) {
	}

	/** One applied operation, with its net change per requested account. */
	public record Posting(String opId, String reason, long created, Map<String, Long> legs) {
	}

	private interface Work<T> {
		T run(Connection connection) throws SQLException;
	}

	private final Logger log;
	private final Predicate<String> frozen;
	private SqliteDatabase database;

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
		return new Ledger(Logger.getLogger("DenarEconomy"), AccountIds::deadCharacter);
	}

	public boolean isOpen() {
		return database != null;
	}

	/**
	 * Applies every leg or none. Legs on the same account are added together first.
	 *
	 * @throws IllegalArgumentException if an ID is blank, an account ID is malformed or the legs
	 *                                  do not sum to zero
	 */
	public Result post(String opId, String reason, List<Leg> legs) {
		requireText(opId, "operation ID");
		requireText(reason, "reason");
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
		Map<String, Long> changes = overflow ? null : net;
		return run("posting " + opId, Result.UNAVAILABLE, connection -> {
			connection.setAutoCommit(false);
			Result result = apply(connection, opId, reason, fingerprint, changes);
			connection.commit();
			connection.setAutoCommit(true);
			return result;
		});
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
			Map<String, Long> changes) throws SQLException {
		String[] stored = stored(connection, opId);
		if (stored != null) {
			if (stored[0].equals(fingerprint)) {
				return Result.valueOf(stored[1]);
			}
			log.warning("Operation " + opId + " was posted again with different legs; its first result stands");
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

	/** The stored fingerprint and status of an operation, or null. */
	private static String[] stored(Connection connection, String opId) throws SQLException {
		try (PreparedStatement query = connection.prepareStatement(
				"SELECT fingerprint, status FROM operations WHERE op_id = ?")) {
			query.setString(1, opId);
			ResultSet row = query.executeQuery();
			return row.next() ? new String[] {row.getString(1), row.getString(2)} : null;
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

	static void requireMainThread() {
		if (!Bukkit.isPrimaryThread()) {
			throw new IllegalStateException("The DenarEconomy ledger must be used on the main thread");
		}
	}
}
