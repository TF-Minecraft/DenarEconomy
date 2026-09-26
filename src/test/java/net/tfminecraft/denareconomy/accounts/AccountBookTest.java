package net.tfminecraft.denareconomy.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
	void failedOfflineChangeRestoresTheUnroundedBalanceAndCanBeRetried() {
		UUID id = UUID.randomUUID();
		AccountBook.Store store = mock(AccountBook.Store.class);
		PlayerData data = account(id, 100.001);
		// setBal supports unrounded legacy balances; rollback must preserve them exactly.
		data.getBank().setBal(100.001);
		when(store.live(id)).thenReturn(data);
		doThrow(new UncheckedIOException(new IOException("disk full"))).when(store).save(data);
		AccountBook book = new AccountBook(store);
		assertThrows(UncheckedIOException.class, () -> book.apply(id, Accounts.BANK, -10, false));
		assertEquals(100.001, data.getBank().getBal());
		verify(store, never()).drop(id);
		doNothing().when(store).save(data);
		assertTrue(book.apply(id, Accounts.BANK, -10, false));
		assertEquals(90, data.getBank().getBal());
		verify(store).drop(id);
	}

	@Test
	void halfCentOverdraftsAreRejectedWithoutSavingOrChangingTheBalance() {
		for (double starting : new double[] {0.0, 0.01}) {
			UUID player = UUID.randomUUID();
			AccountBook.Store store = mock(AccountBook.Store.class);
			PlayerData data = account(player, starting);
			when(store.load(player)).thenReturn(data);
			AccountBook book = new AccountBook(store);
			assertFalse(book.apply(player, Accounts.BANK, -(starting + 0.005), false));
			assertEquals(starting, data.getBank().getBal());
			org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).save(data);
		}
	}

	@Test
	void subHalfCentRemaindersUseTheSameRoundingAsTheActualChange() {
		UUID player = UUID.randomUUID();
		MemoryStore store = new MemoryStore();
		store.disk.put(player, account(player, 0.01));
		AccountBook book = new AccountBook(store);
		assertTrue(book.apply(player, Accounts.BANK, -0.014, false));
		assertEquals(0.0, book.balance(player, Accounts.BANK));
	}

	@Test
	void invalidRequestsNeverTouchTheStore() {
		AccountBook.Store store = mock(AccountBook.Store.class);
		AccountBook book = new AccountBook(store);
		UUID player = UUID.randomUUID();
		assertFalse(book.apply(null, Accounts.BANK, 1, false));
		assertFalse(book.apply(player, null, 1, false));
		assertFalse(book.apply(player, Accounts.BANK, 0, false));
		assertEquals(0, book.balance(null, Accounts.BANK));
		assertEquals(0, book.balance(player, null));
		verifyNoInteractions(store);
	}

	@Test
	void unknownPlayerHasZeroBalance() {
		assertEquals(0, new AccountBook(new MemoryStore()).balance(UUID.randomUUID(), Accounts.POUCH));
	}

	@Test
	void missingAccountRefusesTheChange() {
		UUID player = UUID.randomUUID();
		AccountBook.Store store = mock(AccountBook.Store.class);
		when(store.live(player)).thenReturn(mock(PlayerData.class));
		AccountBook book = new AccountBook(store);
		assertFalse(book.apply(player, Accounts.BANK, 1, false));
		assertEquals(0, book.balance(player, Accounts.BANK));
	}

	@Test
	void onlineDepositLoadsTheSavedAccountIntoTheSession() {
		UUID player = UUID.randomUUID();
		MemoryStore store = new MemoryStore();
		store.online.add(player);
		store.disk.put(player, account(player, 7));
		AccountBook book = new AccountBook(store);
		assertTrue(book.apply(player, Accounts.POUCH, 2.5, false));
		assertEquals(2.5, book.balance(player, Accounts.POUCH));
		assertEquals(7, store.live.get(player).getBank().getBal());
		assertEquals(0, store.disk.get(player).getPouch().getBal());
	}

	@Test
	void onlineDepositCreatesAndKeepsANewAccount() {
		UUID player = UUID.randomUUID();
		MemoryStore store = new MemoryStore();
		store.online.add(player);
		assertTrue(new AccountBook(store).apply(player, Accounts.BANK, 2, false));
		assertEquals(2, store.live.get(player).getBank().getBal());
		assertTrue(store.disk.isEmpty());
	}

	@Test
	void staleOfflineSessionIsSavedAndDropped() {
		UUID player = UUID.randomUUID();
		MemoryStore store = new MemoryStore();
		store.live.put(player, account(player, 8));
		assertTrue(new AccountBook(store).apply(player, Accounts.BANK, -3, false));
		assertEquals(5, store.disk.get(player).getBank().getBal());
		assertFalse(store.live.containsKey(player));
	}

	@Test
	void allowedDebtCreatesAnAccountAndCanIncreaseExistingDebt() {
		UUID player = UUID.randomUUID();
		MemoryStore store = new MemoryStore();
		AccountBook book = new AccountBook(store);
		assertTrue(book.apply(player, Accounts.BANK, -2, true));
		assertTrue(book.apply(player, Accounts.BANK, -3, true));
		assertEquals(-5, book.balance(player, Accounts.BANK));
	}

	@Test
	void withdrawalUsesCentsAndCanConsumeTheExactBalance() {
		UUID player = UUID.randomUUID();
		MemoryStore store = new MemoryStore();
		store.disk.put(player, account(player, 0.3));
		AccountBook book = new AccountBook(store);
		assertTrue(book.apply(player, Accounts.BANK, -(0.1 + 0.2), false));
		assertEquals(0, book.balance(player, Accounts.BANK));
		assertTrue(AccountBook.covers(0.3, -(0.1 + 0.2)));
		assertFalse(AccountBook.covers(0.3, -0.31));
	}

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
