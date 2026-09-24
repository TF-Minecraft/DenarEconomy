package net.tfminecraft.denareconomy.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.tfminecraft.denareconomy.accounts.PlayerNames.CachedName;

class PlayerNamesTest {
	private static final UUID REAL = UUID.fromString("5f4bc018-5ddc-475c-b182-5a158ee5f383");
	private static final UUID OFFLINE = UUID.fromString("98b77a5a-6e04-33be-b7af-ffc7dda570ab");

	@Test
	void accountBeatsAnOfflineModeIdForTheSameName() {
		List<CachedName> rows = List.of(
				new CachedName("Lionmain64", OFFLINE, 1L),
				new CachedName("Lionmain64", REAL, 2L));

		assertEquals(REAL, PlayerNames.choose(rows, id -> id.equals(REAL)));
	}

	@Test
	void mojangIdBeatsAnOfflineModeIdWhenNeitherHasAnAccount() {
		List<CachedName> rows = List.of(
				new CachedName("Lionmain64", OFFLINE, 50L),
				new CachedName("Lionmain64", REAL, 10L));

		assertEquals(REAL, PlayerNames.choose(rows, id -> false));
	}

	@Test
	void resolveIgnoresAnEmptyOfflineModeId() {
		PlayerNames names = new PlayerNames(new Sources() {
			@Override
			public UUID online(String name) {
				return null;
			}

			@Override
			public UUID cached(String name) {
				return OFFLINE;
			}

			@Override
			public List<CachedName> userCache() {
				return List.of(new CachedName("Lionmain64", OFFLINE, 10L));
			}

			@Override
			public boolean hasAccount(UUID id) {
				return false;
			}
		}, null);

		assertNull(names.resolve("Lionmain64"));
	}

	@Test
	void resolveUsesTheSavedAccountWhileThePlayerIsOffline() {
		PlayerNames names = new PlayerNames(new Sources() {
			@Override
			public UUID online(String name) {
				return null;
			}

			@Override
			public UUID cached(String name) {
				return OFFLINE;
			}

			@Override
			public List<CachedName> userCache() {
				return List.of(
						new CachedName("Lionmain64", REAL, 20L),
						new CachedName("Lionmain64", OFFLINE, 90L));
			}

			@Override
			public boolean hasAccount(UUID id) {
				return REAL.equals(id);
			}
		}, null);

		assertEquals(REAL, names.resolve("lionmain64"));
	}

	@Test
	void rememberedNameSurvivesARestart() throws Exception {
		var dir = Files.createTempDirectory("deco-names");
		var file = dir.resolve("player-names.json").toFile();
		Sources sources = new Sources();
		PlayerNames names = new PlayerNames(sources, file);
		names.remember("Wondertopia", REAL);

		PlayerNames again = new PlayerNames(sources, file);
		again.load();

		assertEquals(REAL, again.resolve("Wondertopia"));
	}

	@Test
	void userCacheTextKeepsTheRealId() {
		String json = """
				[
				  {"uuid":"98b77a5a-6e04-33be-b7af-ffc7dda570ab","name":"Lionmain64","expiresOn":"2025-12-01 09:41:25 +0000"},
				  {"uuid":"5f4bc018-5ddc-475c-b182-5a158ee5f383","name":"Lionmain64","expiresOn":"2026-10-23 20:28:02 +0000"}
				]
				""";
		List<CachedName> rows = PlayerNames.parseUserCache(json);
		assertEquals(REAL, PlayerNames.choose(rows, id -> REAL.equals(id)));
	}

	private static class Sources implements PlayerNames.Sources {
		@Override
		public UUID online(String name) {
			return null;
		}

		@Override
		public UUID cached(String name) {
			return null;
		}

		@Override
		public List<CachedName> userCache() {
			return List.of();
		}

		@Override
		public boolean hasAccount(UUID id) {
			return false;
		}
	}
}
