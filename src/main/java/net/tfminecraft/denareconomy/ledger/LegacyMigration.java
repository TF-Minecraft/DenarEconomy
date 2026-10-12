package net.tfminecraft.denareconomy.ledger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import com.google.gson.Gson;

import net.tfminecraft.denareconomy.api.Leg;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.denareconomy.data.Account;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.Characters;

/**
 * Moves pre-ledger account money into the ledger and on to each account's reviewed heir.
 *
 * <p>Each {@code PlayerData/<account>.json} is imported into {@code legacy:<account>:pouch|bank}
 * as operation {@code import:<account>}; once every file is in, the folder is renamed
 * {@code PlayerData.imported}. Once RPCharacters knows the heirs, each legacy account with an heir
 * moves to the heir's pouch and bank as operation {@code claim:<account>}. Both are ordinary
 * postings, so a crash at any point leaves the money in exactly one place and a rerun is a no-op.
 */
public final class LegacyMigration implements Listener {
	private static final Gson GSON = new Gson();
	private static final long POLL_TICKS = 100L;

	/** Unclaimed legacy money of one account, in cents. */
	public record Unclaimed(long pouch, long bank) {
	}

	private final Ledger ledger;
	private final File folder;
	private final Logger log;
	private BukkitTask poll;
	private boolean claimed;

	public LegacyMigration(Ledger ledger, File dataFolder, Logger log) {
		this.ledger = ledger;
		this.folder = new File(dataFolder, "PlayerData");
		this.log = log;
	}

	/**
	 * Imports every account file not yet imported, then renames the folder. A file that cannot be
	 * read or posted keeps the folder in place, so the next start tries again.
	 *
	 * @return whether no folder is left to import
	 */
	public boolean importFiles() {
		File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
		if (files == null) {
			return true;
		}
		boolean complete = true;
		for (File file : files) {
			complete &= importFile(file);
		}
		if (!complete) {
			log.severe("Some PlayerData files were not imported into the ledger; the folder stays until they are fixed");
			return false;
		}
		try {
			Files.move(folder.toPath(), folder.toPath().resolveSibling("PlayerData.imported"));
			log.info("Imported " + files.length + " PlayerData files into the ledger");
			return true;
		} catch (IOException e) {
			log.log(Level.SEVERE, "Imported PlayerData into the ledger but could not rename the folder", e);
			return false;
		}
	}

	private boolean importFile(File file) {
		try {
			String name = file.getName();
			UUID account = UUID.fromString(name.substring(0, name.length() - ".json".length()));
			PlayerData data = GSON.fromJson(Files.readString(file.toPath()), PlayerData.class);
			long pouch = cents(data.getPouch());
			long bank = cents(data.getBank());
			if (pouch < 0 || bank < 0) {
				log.warning("Account " + account + " had a negative balance (pouch " + pouch + ", bank " + bank
						+ " cents); it imports as zero");
			}
			pouch = Math.max(0, pouch);
			bank = Math.max(0, bank);
			Result result = ledger.post("import:" + account, "import", List.of(
					new Leg(AccountIds.MINT, -(pouch + bank)),
					new Leg(AccountIds.legacyPouch(account), pouch),
					new Leg(AccountIds.legacyBank(account), bank)));
			if (result != Result.APPLIED) {
				log.severe("Could not import " + file + ": " + result);
			}
			return result == Result.APPLIED;
		} catch (IOException | RuntimeException e) {
			log.log(Level.SEVERE, "Could not read " + file, e);
			return false;
		}
	}

	private static long cents(Account account) {
		return account == null ? 0 : Math.round(account.getBal() * 100.0);
	}

	/**
	 * Claims for every heir now if RPCharacters already knows them, otherwise as soon as it does:
	 * on any plugin enabling, and every five seconds until then.
	 */
	public void start(Plugin plugin) {
		if (claimWhenReady()) {
			return;
		}
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		poll = plugin.getServer().getScheduler().runTaskTimer(plugin, this::claimWhenReady, POLL_TICKS, POLL_TICKS);
	}

	@EventHandler
	public void onPluginEnable(PluginEnableEvent event) {
		claimWhenReady();
	}

	/** Claims once heirs are known. Returns whether claiming has run. */
	public boolean claimWhenReady() {
		if (claimed || !Characters.legacyHeirsReady()) {
			return claimed;
		}
		claimed = true;
		HandlerList.unregisterAll(this);
		if (poll != null) {
			poll.cancel();
		}
		int count = 0;
		for (UUID account : unclaimed().keySet()) {
			CharacterRef heir = Characters.legacyHeir(account).orElse(null);
			if (heir != null && claim(account, heir) == Result.APPLIED) {
				count++;
			}
		}
		log.info("Moved legacy money of " + count + " accounts to their heirs; " + unclaimed().size()
				+ " wait for staff (/deco legacy)");
		return true;
	}

	/** Moves the account's legacy pouch and bank to this character's pouch and bank. */
	public Result claim(UUID account, CharacterRef heir) {
		String pouch = AccountIds.legacyPouch(account);
		String bank = AccountIds.legacyBank(account);
		long pouchCents = ledger.balance(pouch);
		long bankCents = ledger.balance(bank);
		return ledger.post("claim:" + account, "claim", List.of(
				new Leg(pouch, -pouchCents), new Leg(AccountIds.pouch(heir.characterId()), pouchCents),
				new Leg(bank, -bankCents), new Leg(AccountIds.bank(heir.characterId()), bankCents)));
	}

	/** Legacy accounts still holding money, by account. */
	public Map<UUID, Unclaimed> unclaimed() {
		Map<UUID, long[]> sums = new TreeMap<>();
		for (Ledger.Row row : ledger.accounts("legacy")) {
			long[] sum = sums.computeIfAbsent(UUID.fromString(row.owner()), id -> new long[2]);
			sum[row.label().equals("pouch") ? 0 : 1] = row.cents();
		}
		Map<UUID, Unclaimed> out = new TreeMap<>();
		sums.forEach((account, sum) -> {
			if (sum[0] != 0 || sum[1] != 0) {
				out.put(account, new Unclaimed(sum[0], sum[1]));
			}
		});
		return out;
	}
}
