package net.tfminecraft.denareconomy.accounts;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Leg;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.enums.Accounts;
import net.tfminecraft.tlibs.character.CharacterRef;

/**
 * The pre-ledger account API, kept for one release. It acts only on an online player's current
 * character; for anyone else it refuses and logs the first caller of each method.
 */
public final class LegacyCalls {
	private static final Set<String> logged = new HashSet<>();

	private LegacyCalls() {
	}

	/** The current character of this online player, or empty. */
	public static Optional<CharacterRef> character(UUID playerId) {
		Player player = playerId == null ? null : Bukkit.getPlayer(playerId);
		return player == null ? Optional.empty() : Denars.active(player);
	}

	public static String account(CharacterRef character, Accounts account) {
		return switch (account) {
			case POUCH -> Denars.pouch(character);
			case BANK -> Denars.bank(character);
		};
	}

	/** Logs the first refused call of each method, with its caller. */
	public static void refused(String method) {
		if (logged.add(method)) {
			DenarEconomy.plugin.getLogger().log(Level.WARNING, method + " was refused for a player who is offline"
					+ " or not playing a character. Move the caller to Denars.", new Throwable("Called from"));
		}
	}

	/** Signed change from the mint. Returns whether it was applied. */
	public static boolean apply(UUID playerId, Accounts account, double amount, String method) {
		Optional<CharacterRef> character = character(playerId);
		if (character.isEmpty()) {
			refused(method);
			return false;
		}
		long cents = Math.round(amount * 100.0);
		if (cents == 0) {
			return false;
		}
		List<Leg> legs = List.of(new Leg(Denars.MINT, -cents), new Leg(account(character.get(), account), cents));
		return Denars.post("api:" + UUID.randomUUID(), "api", legs) == Result.APPLIED;
	}

	public static double balance(UUID playerId, Accounts account, String method) {
		Optional<CharacterRef> character = character(playerId);
		if (character.isEmpty()) {
			refused(method);
			return 0.0;
		}
		return Denars.balance(account(character.get(), account)) / 100.0;
	}

	/** Read-only balances of the current character; zero for anyone else. */
	public static PlayerData snapshot(UUID playerId, String method) {
		Optional<CharacterRef> character = character(playerId);
		if (character.isEmpty()) {
			refused(method);
			return PlayerData.snapshot(playerId, 0, 0);
		}
		return PlayerData.snapshot(playerId, Denars.balance(Denars.pouch(character.get())),
				Denars.balance(Denars.bank(character.get())));
	}
}
