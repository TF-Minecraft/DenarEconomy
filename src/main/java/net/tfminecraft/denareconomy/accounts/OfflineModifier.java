package net.tfminecraft.denareconomy.accounts;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import net.tfminecraft.denareconomy.database.Database;
import net.tfminecraft.denareconomy.enums.Accounts;

/**
 * Player names to account IDs, and the name-based balance calls from before characters had their
 * own money. Those calls now act only on an online player's current character; anyone else is
 * refused and logged. Use {@code net.tfminecraft.denareconomy.api.Denars} instead.
 */
public final class OfflineModifier {
	private static final File NAMES = new File("plugins/DenarEconomy/Data/player-names.json");
	private static final File USER_CACHE = new File("usercache.json");

	private static PlayerNames names;

	private OfflineModifier() {
	}

	/** Read the remembered name index. Safe to call more than once. */
	public static void load() {
		names().load();
	}

	/** Signed change on the online player's current character. Returns false for anyone else. */
	@Deprecated
	public static boolean apply(String playerName, Accounts account, double amount) {
		return apply(playerId(playerName), account, amount);
	}

	/** Signed change on the online player's current character. Returns false for anyone else. */
	@Deprecated
	public static boolean apply(UUID playerId, Accounts account, double amount) {
		return LegacyCalls.apply(playerId, account, amount, "OfflineModifier.apply");
	}

	/** Signed change on the online player's current character; ignored for anyone else. */
	@Deprecated
	public static void change(UUID playerId, Accounts account, double amount) {
		LegacyCalls.apply(playerId, account, amount, "OfflineModifier.change");
	}

	@Deprecated
	public static double balance(String playerName, Accounts account) {
		return balance(playerId(playerName), account);
	}

	/** The online player's current character's balance, or zero for anyone else. */
	@Deprecated
	public static double balance(UUID playerId, Accounts account) {
		return LegacyCalls.balance(playerId, account, "OfflineModifier.balance");
	}

	/** The account id for this name, online or offline, or null when deco does not know them. */
	public static UUID playerId(String playerName) {
		return names().resolve(playerName);
	}

	/** Remember a name that just logged in, so later offline charges still find the account. */
	public static void remember(String playerName, UUID playerId) {
		if (playerId == null) {
			return;
		}
		names().remember(playerName, playerId);
	}

	private static PlayerNames names() {
		if (names == null) {
			names = new PlayerNames(new LiveSources(), NAMES);
			names.load();
		}
		return names;
	}

	private static final class LiveSources implements PlayerNames.Sources {
		private List<PlayerNames.CachedName> cacheRows = List.of();
		private long cacheStamp = Long.MIN_VALUE;

		@Override
		public UUID online(String name) {
			if (name == null || Bukkit.getServer() == null) {
				return null;
			}
			Player player = Bukkit.getPlayerExact(name);
			if (player == null || !player.isOnline()) {
				return null;
			}
			return player.getUniqueId();
		}

		@Override
		public UUID cached(String name) {
			if (name == null || Bukkit.getServer() == null) {
				return null;
			}
			OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(name);
			return player == null ? null : player.getUniqueId();
		}

		@Override
		public List<PlayerNames.CachedName> userCache() {
			if (!USER_CACHE.isFile()) {
				return List.of();
			}
			long stamp = USER_CACHE.lastModified();
			if (stamp == cacheStamp) {
				return cacheRows;
			}
			try {
				cacheRows = List.copyOf(PlayerNames.parseUserCache(Files.readString(USER_CACHE.toPath())));
				cacheStamp = stamp;
				return cacheRows;
			} catch (IOException ex) {
				return List.of();
			}
		}

		@Override
		public boolean hasAccount(UUID id) {
			return Database.hasPlayerData(id);
		}
	}
}
