package net.tfminecraft.denareconomy.accounts;

import java.util.UUID;

import net.tfminecraft.denareconomy.data.Account;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.enums.Accounts;

/**
 * Applies a balance change to the live session when the player is online, and to the saved
 * account when they are not. Callers do not choose between those two.
 */
public final class AccountBook {
	public interface Store {
		/** Session copy, or null when this player is not loaded. */
		PlayerData live(UUID id);

		boolean online(UUID id);

		/** Saved account, or null when this player has no file. Does not load them into the session. */
		PlayerData load(UUID id);

		void save(PlayerData data);

		/** Keep a newly created account in the online session. */
		void keep(PlayerData data);

		/** Drop a session copy after it has been written. Used for players who are not online. */
		void drop(UUID id);
	}

	private final Store store;

	public AccountBook(Store store) {
		this.store = store;
	}

	public double balance(UUID id, Accounts account) {
		Account acc = find(id, account);
		return acc == null ? 0.0 : acc.getBal();
	}

	/**
	 * Signed change in denars. A withdrawal that would pass below zero is refused when
	 * {@code allowNegative} is false, and the account is left as it was.
	 */
	public boolean apply(UUID id, Accounts account, double delta, boolean allowNegative) {
		if (id == null || account == null || delta == 0.0) {
			return false;
		}
		boolean session = store.online(id);
		PlayerData data = store.live(id);
		boolean loaded = data != null;
		if (data == null) {
			data = store.load(id);
		}
		if (data == null) {
			if (delta < 0.0 && !allowNegative) {
				return false;
			}
			data = new PlayerData(id);
		}
		Account acc = accountOf(data, account);
		if (acc == null) {
			return false;
		}
		if (!allowNegative && delta < 0.0 && !covers(acc.getBal(), delta)) {
			return false;
		}
		acc.change(delta);
		if (session) {
			if (!loaded) {
				store.keep(data);
			}
			return true;
		}
		store.save(data);
		if (loaded) {
			store.drop(id);
		}
		return true;
	}

	private Account find(UUID id, Accounts account) {
		if (id == null || account == null) {
			return null;
		}
		PlayerData data = store.live(id);
		if (data == null) {
			data = store.load(id);
		}
		return accountOf(data, account);
	}

	private static Account accountOf(PlayerData data, Accounts account) {
		if (data == null || account == null) {
			return null;
		}
		return switch (account) {
			case POUCH -> data.getPouch();
			case BANK -> data.getBank();
		};
	}

	/** True when the balance, in cents, can absorb this negative change. */
	static boolean covers(double balance, double delta) {
		long have = Math.round(balance * 100.0);
		long cents = Math.round(delta * 100.0);
		return have + cents >= 0;
	}
}
