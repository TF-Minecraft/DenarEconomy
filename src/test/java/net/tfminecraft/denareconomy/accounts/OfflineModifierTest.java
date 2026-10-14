package net.tfminecraft.denareconomy.accounts;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.database.Database;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class OfflineModifierTest {
  private static final Path NAMES = Path.of("plugins/DenarEconomy/Data/player-names.json");
  private static final Path CACHE = Path.of("usercache.json");
  private final Map<Path, Path> backups = new HashMap<>();
  private Object previousNames;

  @BeforeEach
  void isolateStaticsAndFiles() throws Exception {
    previousNames = field("names").get(null);
    field("names").set(null, null);
    for (Path path : new Path[] {NAMES, CACHE}) {
      if (Files.exists(path)) {
        Path backup = Files.createTempDirectory("denar-names-backup").resolve("file");
        Files.move(path, backup);
        backups.put(path, backup);
      }
    }
  }

  @AfterEach
  void restoreStaticsAndFiles() throws Exception {
    field("names").set(null, previousNames);
    for (Path path : new Path[] {NAMES, CACHE}) {
      Files.deleteIfExists(path);
      Path backup = backups.get(path);
      if (backup != null) {
        Files.move(backup, path);
        Files.delete(backup.getParent());
      }
    }
  }

  @Test
  void rememberedNamesSurviveReloadAndUnknownNamesAreRefused() throws Exception {
    DenarEconomy previous = DenarEconomy.plugin;
    DenarEconomy.plugin = mock(DenarEconomy.class);
    when(DenarEconomy.plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      UUID id = UUID.randomUUID();
      OfflineModifier.remember("Ignored", null);
      assertNull(field("names").get(null));
      OfflineModifier.load();
      assertNull(OfflineModifier.playerId("Unknown"));
      OfflineModifier.remember("Alex", id);
      assertTrue(Files.readString(NAMES).contains(id.toString()));
      field("names").set(null, null);
      OfflineModifier.load();
      OfflineModifier.load();
      assertEquals(id, OfflineModifier.playerId("ALEX"));
    } finally {
      DenarEconomy.plugin = previous;
    }
  }

  @Test
  void liveSourcesResolveOnlineAndCachedPlayersOnlyWhenServerExists() throws Exception {
    PlayerNames.Sources sources = adapter("LiveSources", PlayerNames.Sources.class);
    UUID id = UUID.randomUUID();
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      assertNull(sources.online(null));
      assertNull(sources.online("Alex"));
      assertNull(sources.cached(null));
      assertNull(sources.cached("Alex"));
      bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));
      assertNull(sources.online("Alex"));
      assertNull(sources.cached("Alex"));
      Player player = mock(Player.class);
      bukkit.when(() -> Bukkit.getPlayerExact("Alex")).thenReturn(player);
      assertNull(sources.online("Alex"));
      when(player.isOnline()).thenReturn(true);
      when(player.getUniqueId()).thenReturn(id);
      assertEquals(id, sources.online("Alex"));
      OfflinePlayer cached = mock(OfflinePlayer.class);
      when(cached.getUniqueId()).thenReturn(id);
      bukkit.when(() -> Bukkit.getOfflinePlayerIfCached("Alex")).thenReturn(cached);
      assertEquals(id, sources.cached("Alex"));
    }
  }

  @Test
  void liveSourcesTreatAPlayerWithACurrentCharacterAsHavingAnAccount() throws Exception {
    PlayerNames.Sources sources = adapter("LiveSources", PlayerNames.Sources.class);
    UUID id = UUID.randomUUID();
    try (MockedStatic<Database> database = mockStatic(Database.class)) {
      assertFalse(sources.hasAccount(id));
      database.when(() -> Database.hasPlayerData(id)).thenReturn(true);
      assertTrue(sources.hasAccount(id));
    }
  }

  @Test
  void userCacheIsReusedUntilTimestampChangesAndReadErrorsCanRecover() throws Exception {
    PlayerNames.Sources sources = adapter("LiveSources", PlayerNames.Sources.class);
    assertTrue(sources.userCache().isEmpty());
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Files.writeString(CACHE, cacheRow("First", first));
    Files.setLastModifiedTime(CACHE, FileTime.fromMillis(10_000));
    var firstRows = sources.userCache();
    assertEquals(first, firstRows.get(0).id());
    assertSame(firstRows, sources.userCache());
    Files.writeString(CACHE, cacheRow("Second", second));
    Files.setLastModifiedTime(CACHE, FileTime.fromMillis(20_000));
    try (MockedStatic<Files> files = mockStatic(Files.class)) {
      files
          .when(() -> Files.readString(CACHE))
          .thenThrow(new IOException("transient read failure"));
      assertTrue(sources.userCache().isEmpty());
    }
    var secondRows = sources.userCache();
    assertEquals("Second", secondRows.get(0).name());
    assertEquals(second, secondRows.get(0).id());
    assertSame(secondRows, sources.userCache());
  }

  private static String cacheRow(String name, UUID id) {
    return "[{\"name\":\"" + name + "\",\"uuid\":\"" + id + "\"}]";
  }

  private static Field field(String name) throws Exception {
    Field field = OfflineModifier.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static <T> T adapter(String name, Class<T> type) throws Exception {
    var constructor =
        Class.forName(OfflineModifier.class.getName() + "$" + name).getDeclaredConstructor();
    constructor.setAccessible(true);
    return type.cast(constructor.newInstance());
  }
}
