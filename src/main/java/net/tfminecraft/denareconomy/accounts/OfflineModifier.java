package net.tfminecraft.denareconomy.accounts;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.database.Database;
import net.tfminecraft.denareconomy.enums.Accounts;
import net.tfminecraft.denareconomy.managers.PlayerManager;

/**
 * A balance change other plugins can apply without asking whether the player is logged in.
 * The name is resolved here. A withdrawal that the account cannot cover is refused.
 */
public final class OfflineModifier {
	private static final File NAMES = new File("plugins/DenarEconomy/Data/player-names.json");
	private static final File USER_CACHE = new File("usercache.json");

	private static AccountBook book;
	private static PlayerNames names;

	private OfflineModifier() {
	}

	/** Read the remembered name index. Safe to call more than once. */
	public static void load() {
		names().load();
	}

	/**
	 * Move {@code amount} denars on this account. Positive deposits, negative withdraws.
	 * Returns false when the player is unknown or the withdrawal would pass below zero.
	 */
	public static boolean apply(String playerName, Accounts account, double amount) {
		UUID id = playerId(playerName);
		if (id == null) {
			return false;
		}
		return apply(id, account, amount);
	}

	/**
	 * Move {@code amount} denars on this account. Positive deposits, negative withdraws.
	 * Returns false when the withdrawal would pass below zero.
	 */
	public static boolean apply(UUID playerId, Accounts account, double amount) {
		if (playerId == null || account == null) {
			return false;
		}
		return book().apply(playerId, account, amount, false);
	}

	/**
	 * Legacy write used by {@code MoneyManager.changeBal}. A withdrawal may pass below zero
	 * because those callers already decided the amount.
	 */
	public static void change(UUID playerId, Accounts account, double amount) {
		if (playerId == null || account == null || amount == 0.0) {
			return;
		}
		book().apply(playerId, account, amount, true);
	}

	public static double balance(String playerName, Accounts account) {
		UUID id = playerId(playerName);
		if (id == null) {
			return 0.0;
		}
		return balance(id, account);
	}

	public static double balance(UUID playerId, Accounts account) {
		if (playerId == null || account == null) {
			return 0.0;
		}
		return book().balance(playerId, account);
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

	private static AccountBook book() {
		if (book == null) {
			book = new AccountBook(new LiveStore());
		}
		return book;
	}

	private static PlayerNames names() {
		if (names == null) {
			names = new PlayerNames(new LiveSources(), NAMES);
			names.load();
		}
		return names;
	}

	private static final class LiveStore implements AccountBook.Store {
		@Override
		public PlayerData live(UUID id) {
			PlayerManager manager = DenarEconomy.getPlayerManager();
			if (manager == null || id == null || !manager.exists(id)) {
				return null;
			}
			return manager.peek(id);
		}

		@Override
		public boolean online(UUID id) {
			if (id == null || Bukkit.getServer() == null) {
				return false;
			}
			Player player = Bukkit.getPlayer(id);
			return player != null && player.isOnline();
		}

		@Override
		public PlayerData load(UUID id) {
			if (id == null || !Database.hasPlayerData(id)) {
				return null;
			}
			return Database.loadPlayerData(id);
		}

		@Override
		public void save(PlayerData data) {
			if (data != null) {
				Database.savePlayerData(data);
			}
		}

		@Override
		public void keep(PlayerData data) {
			PlayerManager manager = DenarEconomy.getPlayerManager();
			if (manager != null) {
				manager.keep(data);
			}
		}

		@Override
		public void drop(UUID id) {
			PlayerManager manager = DenarEconomy.getPlayerManager();
			if (manager != null) {
				manager.drop(id);
			}
		}
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
			if (id == null) {
				return false;
			}
			PlayerManager manager = DenarEconomy.getPlayerManager();
			if (manager != null && manager.exists(id)) {
				return true;
			}
			return Database.hasPlayerData(id);
		}
	}
}
