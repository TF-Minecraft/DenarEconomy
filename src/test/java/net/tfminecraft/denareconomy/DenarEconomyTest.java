package net.tfminecraft.denareconomy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.tfminecraft.denareconomy.accounts.OfflineModifier;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.database.Database;
import net.tfminecraft.denareconomy.loaders.CoinLoader;
import net.tfminecraft.denareconomy.loaders.DropLoader;
import net.tfminecraft.denareconomy.loaders.MessageLoader;
import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

class DenarEconomyTest {
  @TempDir Path directory;

  @Test
  void createsMissingFoldersAndOnlySavesMissingDefaultConfigs() throws Exception {
    DenarEconomy plugin = mock(DenarEconomy.class, CALLS_REAL_METHODS);
    Path data = directory.resolve("DenarEconomy");
    doReturn(data.toFile()).when(plugin).getDataFolder();
    doNothing().when(plugin).saveResource(anyString(), eq(false));
    plugin.createFolders();
    assertTrue(Files.isDirectory(data.resolve("Data")));
    assertTrue(Files.isDirectory(data.resolve("PlayerData")));
    plugin.createFolders();
    Files.writeString(data.resolve("coins.yml"), "keep: existing");
    plugin.createConfigs();
    verify(plugin, never()).saveResource("coins.yml", false);
    verify(plugin).saveResource("drops.yml", false);
    verify(plugin).saveResource("messages.yml", false);
    assertEquals("keep: existing", Files.readString(data.resolve("coins.yml")));
  }

  @Test
  void disableRetriesOfflineSessionsAndContinuesSavingAndCleaningUpAfterFailure() {
    UUID failedId = UUID.randomUUID();
    UUID savedId = UUID.randomUUID();
    PlayerData failed = new PlayerData(failedId);
    PlayerData saved = new PlayerData(savedId);
    failed.getBank().setBal(200);
    saved.getBank().setBal(300);
    var manager = DenarEconomy.getPlayerManager();
    var money = DenarEconomy.getMoneyManager();
    manager.keep(failed);
    manager.keep(saved);
    ArmorStand stand = mock(ArmorStand.class);
    money.getStandMap().put(failedId, stand);
    money.getTaskMap().put(failedId, 17);
    var failure = new java.io.UncheckedIOException(new java.io.IOException("disk unavailable"));
    var logger = mock(java.util.logging.Logger.class);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    DenarEconomy plugin = mock(DenarEconomy.class, CALLS_REAL_METHODS);
    try (var database = mockStatic(Database.class);
        var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
      bukkit.when(Bukkit::getLogger).thenReturn(logger);
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      database.when(() -> Database.savePlayerData(failed)).thenThrow(failure);
      assertDoesNotThrow(plugin::onDisable);
      assertSame(failed, manager.peek(failedId));
      assertFalse(manager.exists(savedId));
      database.verify(() -> Database.savePlayerData(saved));
      verify(logger)
          .log(eq(java.util.logging.Level.SEVERE), contains(failedId.toString()), same(failure));
      verify(stand).remove();
      verify(scheduler).cancelTask(17);
      assertTrue(money.getStandMap().isEmpty());
      assertTrue(money.getTaskMap().isEmpty());
      database.when(() -> Database.savePlayerData(failed)).thenAnswer(invocation -> null);
      plugin.onDisable();
      assertFalse(manager.exists(failedId));
      database.verify(() -> Database.savePlayerData(failed), times(2));
    } finally {
      manager.drop(failedId);
      manager.drop(savedId);
      money.getStandMap().clear();
      money.getTaskMap().clear();
    }
  }

  @Test
  void enableLoadsConfigsRegistersCommandsAndSessionsAndDisableCleansUp() {
    DenarEconomy previous = DenarEconomy.plugin;
    UUID id = UUID.randomUUID();
    try (var database = mockStatic(Database.class);
        var offline = mockStatic(OfflineModifier.class);
        var coins = mockConstruction(CoinLoader.class);
        var drops = mockConstruction(DropLoader.class);
        var messages = mockConstruction(MessageLoader.class)) {
      var server = MockBukkit.mock();
      Player online = server.addPlayer();
      id = online.getUniqueId();
      UUID playerId = id;
      DenarEconomy plugin =
          MockBukkit.loadWith(
              DenarEconomy.class,
              new java.io.ByteArrayInputStream(
                  """
                  name: DenarEconomy
                  main: net.tfminecraft.denareconomy.DenarEconomy
                  version: test
                  api-version: '1.21'
                  commands:
                    deco: {}
                    pouch: {}
                  """
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      assertSame(plugin, DenarEconomy.plugin);
      assertNotNull(plugin.getCommand("deco").getExecutor());
      assertSame(plugin.getCommand("deco").getExecutor(), plugin.getCommand("pouch").getExecutor());
      assertSame(
          plugin.getCommand("deco").getExecutor(), plugin.getCommand("deco").getTabCompleter());
      assertSame(
          plugin.getCommand("pouch").getExecutor(), plugin.getCommand("pouch").getTabCompleter());
      assertTrue(DenarEconomy.getPlayerManager().exists(online));
      offline.verify(OfflineModifier::load);
      offline.verify(() -> OfflineModifier.remember(online.getName(), playerId));
      verify(coins.constructed().getFirst())
          .loadCoins(new File(plugin.getDataFolder(), "coins.yml"));
      verify(drops.constructed().getFirst()).load(new File(plugin.getDataFolder(), "drops.yml"));
      verify(messages.constructed().getFirst())
          .load(new File(plugin.getDataFolder(), "messages.yml"));
      for (String name : List.of("coins.yml", "drops.yml", "messages.yml")) {
        assertTrue(new File(plugin.getDataFolder(), name).isFile());
      }
      PlayerData session = DenarEconomy.getPlayerManager().get(online);
      ArmorStand live = mock(ArmorStand.class);
      ArmorStand dead = mock(ArmorStand.class);
      when(dead.isDead()).thenReturn(true);
      var money = DenarEconomy.getMoneyManager();
      money.getStandMap().put(id, live);
      money.getStandMap().put(UUID.randomUUID(), dead);
      money.getStandMap().put(UUID.randomUUID(), null);
      money.getTaskMap().put(id, 17);
      BukkitScheduler scheduler = mock(BukkitScheduler.class);
      try (var bukkit = mockStatic(Bukkit.class)) {
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(online));
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        plugin.onDisable();
        database.verify(() -> Database.savePlayerData(session));
        verify(live).remove();
        verify(dead, never()).remove();
        verify(scheduler).cancelTask(17);
        assertTrue(money.getStandMap().isEmpty());
        assertTrue(money.getTaskMap().isEmpty());
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        plugin.onDisable();
        verifyNoMoreInteractions(scheduler);
      }
    } finally {
      try (var database = mockStatic(Database.class)) {
        MockBukkit.unmock();
      }
      DenarEconomy.getPlayerManager().drop(id);
      DenarEconomy.getMoneyManager().getStandMap().clear();
      DenarEconomy.getMoneyManager().getTaskMap().clear();
      DenarEconomy.plugin = previous;
    }
  }
}
