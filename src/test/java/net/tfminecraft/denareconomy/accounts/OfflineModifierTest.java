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
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.database.Database;
import net.tfminecraft.denareconomy.enums.Accounts;
import net.tfminecraft.denareconomy.managers.PlayerManager;
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
  private Object previousBook;
  private Object previousNames;

  @BeforeEach
  void isolateStaticsAndFiles() throws Exception {
    previousBook = field("book").get(null);
    previousNames = field("names").get(null);
    field("book").set(null, null);
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
    field("book").set(null, previousBook);
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
  void invalidArgumentsDoNotTouchAccountsAndChangesSelectWithdrawalPolicy() throws Exception {
    AccountBook book = mock(AccountBook.class);
    field("book").set(null, book);
    UUID id = UUID.randomUUID();
    assertFalse(OfflineModifier.apply((UUID) null, Accounts.BANK, 1));
    assertFalse(OfflineModifier.apply(id, null, 1));
    assertEquals(0, OfflineModifier.balance((UUID) null, Accounts.POUCH));
    assertEquals(0, OfflineModifier.balance(id, null));
    OfflineModifier.change(null, Accounts.POUCH, 1);
    OfflineModifier.change(id, null, 1);
    OfflineModifier.change(id, Accounts.POUCH, 0);
    verifyNoInteractions(book);

    when(book.apply(id, Accounts.BANK, 2, false)).thenReturn(true);
    when(book.balance(id, Accounts.BANK)).thenReturn(42.5);
    assertTrue(OfflineModifier.apply(id, Accounts.BANK, 2));
    assertFalse(OfflineModifier.apply(id, Accounts.BANK, -100));
    OfflineModifier.change(id, Accounts.POUCH, -100);
    verify(book).apply(id, Accounts.POUCH, -100, true);
    assertEquals(42.5, OfflineModifier.balance(id, Accounts.BANK));
  }

  @Test
  void rememberedNamesSurviveReloadAndUnknownNamesDoNotAccessAccounts() throws Exception {
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      UUID id = UUID.randomUUID();
      AccountBook book = mock(AccountBook.class);
      field("book").set(null, book);
      OfflineModifier.remember("Ignored", null);
      assertNull(field("names").get(null));
      OfflineModifier.load();
      assertNull(OfflineModifier.playerId("Unknown"));
      assertFalse(OfflineModifier.apply("Unknown", Accounts.POUCH, 1));
      assertEquals(0, OfflineModifier.balance("Unknown", Accounts.BANK));
      verifyNoInteractions(book);
      OfflineModifier.remember("Alex", id);
      assertTrue(Files.readString(NAMES).contains(id.toString()));
      field("names").set(null, null);
      OfflineModifier.load();
      OfflineModifier.load();
      assertEquals(id, OfflineModifier.playerId("ALEX"));
      when(book.apply(id, Accounts.BANK, 1, false)).thenReturn(true);
      when(book.balance(id, Accounts.BANK)).thenReturn(9.5);
      assertTrue(OfflineModifier.apply("Alex", Accounts.BANK, 1));
      assertEquals(9.5, OfflineModifier.balance("Alex", Accounts.BANK));
    }
  }

  @Test
  void lazilyCreatedBookReadsPersistedAccounts() {
    UUID id = UUID.randomUUID();
    PlayerData data = new PlayerData(id);
    data.getPouch().setBal(14);
    try (MockedStatic<DenarEconomy> plugin = mockStatic(DenarEconomy.class);
        MockedStatic<Database> database = mockStatic(Database.class);
        MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      database.when(() -> Database.hasPlayerData(id)).thenReturn(true);
      database.when(() -> Database.loadPlayerData(id)).thenReturn(data);
      assertEquals(14, OfflineModifier.balance(id, Accounts.POUCH));
      assertTrue(OfflineModifier.apply(id, Accounts.POUCH, 2));
      assertEquals(16, data.getPouch().getBal());
      database.verify(() -> Database.savePlayerData(data));
    }
  }

  @Test
  void liveStoreHandlesAbsentManagerAndDelegatesSessionLifecycle() throws Exception {
    AccountBook.Store store = adapter("LiveStore", AccountBook.Store.class);
    UUID id = UUID.randomUUID();
    PlayerData data = new PlayerData(id);
    try (MockedStatic<DenarEconomy> plugin = mockStatic(DenarEconomy.class);
        MockedStatic<Database> database = mockStatic(Database.class)) {
      assertNull(store.live(id));
      store.keep(data);
      store.drop(id);
      PlayerManager manager = mock(PlayerManager.class);
      plugin.when(DenarEconomy::getPlayerManager).thenReturn(manager);
      assertNull(store.live(null));
      assertNull(store.live(id));
      when(manager.exists(id)).thenReturn(true);
      when(manager.peek(id)).thenReturn(data);
      assertSame(data, store.live(id));
      store.keep(data);
      store.drop(id);
      verify(manager).keep(data);
      verify(manager).drop(id);

      assertNull(store.load(null));
      assertNull(store.load(id));
      database.when(() -> Database.hasPlayerData(id)).thenReturn(true);
      database.when(() -> Database.loadPlayerData(id)).thenReturn(data);
      assertSame(data, store.load(id));
      store.save(null);
      database.verify(() -> Database.savePlayerData(null), never());
      store.save(data);
      database.verify(() -> Database.savePlayerData(data));
    }
  }

  @Test
  void liveStoreChecksServerAndActualPlayerOnlineStatus() throws Exception {
    AccountBook.Store store = adapter("LiveStore", AccountBook.Store.class);
    UUID id = UUID.randomUUID();
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      assertFalse(store.online(null));
      assertFalse(store.online(id));
      bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));
      assertFalse(store.online(id));
      Player player = mock(Player.class);
      bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
      assertFalse(store.online(id));
      when(player.isOnline()).thenReturn(true);
      assertTrue(store.online(id));
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
  void liveSourcesPreferLoadedAccountThenCheckDatabase() throws Exception {
    PlayerNames.Sources sources = adapter("LiveSources", PlayerNames.Sources.class);
    UUID id = UUID.randomUUID();
    try (MockedStatic<DenarEconomy> plugin = mockStatic(DenarEconomy.class);
        MockedStatic<Database> database = mockStatic(Database.class)) {
      assertFalse(sources.hasAccount(null));
      assertFalse(sources.hasAccount(id));
      database.when(() -> Database.hasPlayerData(id)).thenReturn(true);
      assertTrue(sources.hasAccount(id));
      PlayerManager manager = mock(PlayerManager.class);
      plugin.when(DenarEconomy::getPlayerManager).thenReturn(manager);
      assertTrue(sources.hasAccount(id));
      database.clearInvocations();
      when(manager.exists(id)).thenReturn(true);
      assertTrue(sources.hasAccount(id));
      database.verifyNoInteractions();
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
