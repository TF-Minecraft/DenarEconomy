package net.tfminecraft.denareconomy.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.denareconomy.accounts.PlayerNames.CachedName;

class PlayerNamesTest {
	@TempDir
	Path directory;
	private static final UUID REAL = UUID.fromString("5f4bc018-5ddc-475c-b182-5a158ee5f383");
	private static final UUID OFFLINE = UUID.fromString("98b77a5a-6e04-33be-b7af-ffc7dda570ab");
	private static final UUID OTHER = UUID.fromString("d3a509c0-645c-4f2f-9ad8-bd045cb8aec7");

	@Test
	void emptyOrDamagedCandidatesHaveNoWinner() {
		assertNull(PlayerNames.choose(null, null));
		assertNull(PlayerNames.choose(List.of(), null));
		List<CachedName> damaged = Arrays.asList(null, new CachedName("name", null, 1));
		assertNull(PlayerNames.choose(damaged, null));
		assertNull(PlayerNames.choose(damaged, id -> true));
	}

	@Test
	void existingOfflineAccountWinsOverAnUnfundedMojangId() {
		assertEquals(OFFLINE, PlayerNames.choose(List.of(
				new CachedName("name", OFFLINE, 1), new CachedName("name", REAL, 10)), OFFLINE::equals));
	}

	@Test
	void newestExpiryWinsAndTiesUseTheLastRow() {
		assertEquals(REAL, PlayerNames.choose(List.of(
				new CachedName("name", OTHER, 5), new CachedName("name", REAL, 10),
				new CachedName("name", OTHER, 1)), null));
		assertEquals(OTHER, PlayerNames.choose(List.of(
				new CachedName("name", REAL, 10), new CachedName("name", OTHER, 10)), null));
	}

	@Test
	void invalidNamesDoNotQuerySourcesOrWriteAnIndex() {
		PlayerNames.Sources sources = mock(PlayerNames.Sources.class);
		File index = directory.resolve("names.json").toFile();
		PlayerNames names = new PlayerNames(sources, index);
		assertNull(names.resolve(null));
		assertNull(names.resolve(" \t"));
		assertNull(new PlayerNames(null, null).resolve("name"));
		names.remember(null, REAL);
		names.remember(" \t", REAL);
		names.remember("name", null);
		assertFalse(index.exists());
		verifyNoInteractions(sources);
	}

	@Test
	void onlineIdentityOverridesAndUpdatesRememberedNames() {
		PlayerNames.Sources sources = mock(PlayerNames.Sources.class);
		when(sources.online("Name")).thenReturn(REAL);
		PlayerNames names = new PlayerNames(sources, null);
		names.remember("name", OTHER);
		assertEquals(REAL, names.resolve("Name"));
		assertEquals(REAL, names.resolve("NAME"));
	}

	@Test
	void cacheMatchesAreCaseInsensitiveAndIgnoreUnrelatedOrDamagedRows() {
		PlayerNames.Sources sources = mock(PlayerNames.Sources.class);
		when(sources.userCache()).thenReturn(Arrays.asList(null,
				new CachedName(null, OTHER, 20), new CachedName("someoneElse", OTHER, 20),
				new CachedName("nAmE", REAL, 1)));
		assertEquals(REAL, new PlayerNames(sources, null).resolve("NAME"));
	}

	@Test
	void cachedOfflineIdentityWithAnAccountIsAccepted() {
		PlayerNames.Sources sources = mock(PlayerNames.Sources.class);
		when(sources.cached("name")).thenReturn(OFFLINE);
		when(sources.hasAccount(OFFLINE)).thenReturn(true);
		assertEquals(OFFLINE, new PlayerNames(sources, null).resolve("name"));
	}

	@Test
	void missingCacheFallsBackToRememberedName() {
		PlayerNames.Sources sources = mock(PlayerNames.Sources.class);
		when(sources.userCache()).thenReturn(null);
		PlayerNames names = new PlayerNames(sources, null);
		assertNull(names.resolve("unknown"));
		names.remember("name", REAL);
		assertEquals(REAL, names.resolve("NAME"));
	}

	@Test
	void loadIgnoresMissingEmptyAndUnreadableIndexes() throws Exception {
		new PlayerNames(new Sources(), null).load();
		new PlayerNames(new Sources(), directory.resolve("missing.json").toFile()).load();
		Path file = directory.resolve("empty.json");
		Files.writeString(file, "null");
		PlayerNames names = new PlayerNames(new Sources(), file.toFile());
		names.load();
		assertNull(names.resolve("name"));
		File disappearing = mock(File.class);
		when(disappearing.isFile()).thenReturn(true);
		when(disappearing.getPath()).thenReturn(directory.resolve("gone.json").toString());
		new PlayerNames(new Sources(), disappearing).load();
	}

	@Test
	void loadSkipsDamagedRowsAndNormalizesValidNames() throws Exception {
		Path file = directory.resolve("names.json");
		Files.writeString(file, "[[\"missing\",null],"
				+ "[\"broken\",\"invalid uuid\"],[\"NaMe\",\"" + REAL + "\"]]");
		PlayerNames names = new PlayerNames(new Sources(), file.toFile());
		names.load();
		assertEquals(REAL, names.resolve("NAME"));
		assertNull(names.resolve("broken"));
		assertNull(names.resolve("missing"));
	}

	@Test
	void saveCreatesDirectoriesAndDoesNotRewriteUnchangedNames() throws Exception {
		Path file = directory.resolve("nested/names.json");
		PlayerNames names = new PlayerNames(new Sources(), file.toFile());
		names.remember("Name", REAL);
		assertTrue(Files.readString(file).contains(REAL.toString()));
		Files.delete(file);
		names.remember("NAME", REAL);
		assertFalse(Files.exists(file));
		names.remember("NAME", OTHER);
		assertTrue(Files.readString(file).contains(OTHER.toString()));
	}

	@Test
	void failedWritesStillRememberIdentityInMemory() throws Exception {
		Path blocker = directory.resolve("file");
		Files.writeString(blocker, "not a directory");
		PlayerNames blocked = new PlayerNames(new Sources(), blocker.resolve("nested/names.json").toFile());
		blocked.remember("name", REAL);
		assertEquals(REAL, blocked.resolve("name"));
		PlayerNames unwritable = new PlayerNames(new Sources(), directory.toFile());
		unwritable.remember("name", REAL);
		assertEquals(REAL, unwritable.resolve("name"));
	}

	@Test
	void saveWorksWhenTheIndexHasNoParent() throws Exception {
		Path path = directory.resolve("names.json");
		File file = mock(File.class);
		when(file.getPath()).thenReturn(path.toString());
		PlayerNames names = new PlayerNames(new Sources(), file);
		names.remember("name", REAL);
		assertTrue(Files.readString(path).contains(REAL.toString()));
	}

	@Test
	void absentUserCacheTextIsEmpty() {
		assertTrue(PlayerNames.parseUserCache(null).isEmpty());
		assertTrue(PlayerNames.parseUserCache(" \t").isEmpty());
		assertTrue(PlayerNames.parseUserCache("null").isEmpty());
		assertTrue(PlayerNames.parseUserCache("[]").isEmpty());
	}

	@Test
	void userCacheSkipsInvalidRowsAndDefaultsInvalidExpiriesToZero() {
		String json = """
				[null, {}, {"name":"missing"}, {"uuid":"invalid","name":"broken"},
				 {"uuid":"%s","name":"missing expiry"},
				 {"uuid":"%s","name":"blank expiry","expiresOn":" "},
				 {"uuid":"%s","name":"bad expiry","expiresOn":"tomorrow"},
				 {"uuid":"%s","name":"valid expiry","expiresOn":"2026-10-23 22:28:02 +0200"}]
				""".formatted(REAL, REAL, REAL, REAL);
		List<CachedName> rows = PlayerNames.parseUserCache(json);
		assertEquals(4, rows.size());
		assertEquals(new CachedName("missing expiry", REAL, 0), rows.get(0));
		assertEquals(0, rows.get(1).expiresAtMillis());
		assertEquals(0, rows.get(2).expiresAtMillis());
		assertEquals(Instant.parse("2026-10-23T20:28:02Z").toEpochMilli(), rows.get(3).expiresAtMillis());
	}

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
		var file = directory.resolve("player-names.json").toFile();
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
