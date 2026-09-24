package net.tfminecraft.denareconomy.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.enums.Accounts;

class AccountBookTest {
	@Test
	void offlineWithdrawalIsSavedAndDoesNotJoinTheSession() {
		UUID player = UUID.randomUUID();
		MemoryStore store = new MemoryStore();
		store.disk.put(player, account(player, 40.0));
		AccountBook book = new AccountBook(store);

		assertTrue(book.apply(player, Accounts.BANK, -15.0, false));

		assertEquals(25.0, store.disk.get(player).getBank().getBal());
		assertNull(store.live.get(player));
		assertEquals(25.0, book.balance(player, Accounts.BANK));
	}

	@Test
	void onlineWithdrawalStaysInTheSessionUntilQuit() {
		UUID player = UUID.randomUUID();
		MemoryStore store = new MemoryStore();
		store.online.add(player);
		PlayerData session = account(player, 40.0);
		store.live.put(player, session);
		store.disk.put(player, account(player, 40.0));
		AccountBook book = new AccountBook(store);

		assertTrue(book.apply(player, Accounts.BANK, -10.0, false));

		assertEquals(30.0, session.getBank().getBal());
		assertEquals(40.0, store.disk.get(player).getBank().getBal());
		assertEquals(30.0, book.balance(player, Accounts.BANK));
	}

	@Test
	void withdrawalPastZeroIsRefused() {
		UUID player = UUID.randomUUID();
		MemoryStore store = new MemoryStore();
		store.disk.put(player, account(player, 4.0));
		AccountBook book = new AccountBook(store);

		assertFalse(book.apply(player, Accounts.BANK, -5.0, false));
		assertEquals(4.0, book.balance(player, Accounts.BANK));
	}

	@Test
	void offlineDepositCreatesAnAccountOnDisk() {
		UUID player = UUID.randomUUID();
		MemoryStore store = new MemoryStore();
		AccountBook book = new AccountBook(store);

		assertTrue(book.apply(player, Accounts.BANK, 12.5, false));

		assertEquals(12.5, store.disk.get(player).getBank().getBal());
		assertNull(store.live.get(player));
	}

	@Test
	void unknownPlayerCannotBeCharged() {
		MemoryStore store = new MemoryStore();
		AccountBook book = new AccountBook(store);

		assertFalse(book.apply(UUID.randomUUID(), Accounts.POUCH, -1.0, false));
		assertTrue(store.disk.isEmpty());
	}

	private static PlayerData account(UUID id, double bank) {
		PlayerData data = new PlayerData(id);
		data.getBank().setBal(bank);
		return data;
	}

	private static final class MemoryStore implements AccountBook.Store {
		private final Map<UUID, PlayerData> live = new HashMap<>();
		private final Map<UUID, PlayerData> disk = new HashMap<>();
		private final Set<UUID> online = new HashSet<>();

		@Override
		public PlayerData live(UUID id) {
			return live.get(id);
		}

		@Override
		public boolean online(UUID id) {
			return online.contains(id);
		}

		@Override
		public PlayerData load(UUID id) {
			PlayerData data = disk.get(id);
			if (data == null) {
				return null;
			}
			PlayerData copy = new PlayerData(id);
			copy.getPouch().setBal(data.getPouch().getBal());
			copy.getBank().setBal(data.getBank().getBal());
			return copy;
		}

		@Override
		public void save(PlayerData data) {
			disk.put(data.getId(), data);
		}

		@Override
		public void keep(PlayerData data) {
			live.put(data.getId(), data);
		}

		@Override
		public void drop(UUID id) {
			live.remove(id);
		}
	}
}
