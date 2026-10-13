package net.tfminecraft.denareconomy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.tfminecraft.denareconomy.accounts.OfflineModifier;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.ledger.Ledger;
import net.tfminecraft.denareconomy.loaders.CoinLoader;
import net.tfminecraft.denareconomy.loaders.DropLoader;
import net.tfminecraft.denareconomy.loaders.MessageLoader;
import net.tfminecraft.denareconomy.testing.FakeIdentity;
import net.tfminecraft.tlibs.character.CharacterRef;
import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

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
    assertFalse(Files.exists(data.resolve("PlayerData")), "Legacy account files are no longer written");
    plugin.createFolders();
    Files.writeString(data.resolve("coins.yml"), "keep: existing");
    plugin.createConfigs();
    verify(plugin, never()).saveResource("coins.yml", false);
    verify(plugin).saveResource("drops.yml", false);
    verify(plugin).saveResource("messages.yml", false);
    assertEquals("keep: existing", Files.readString(data.resolve("coins.yml")));
  }

  @Test
  void enableOpensTheLedgerMovesLegacyMoneyAndDisableClosesItAndCleansUp() throws Exception {
    DenarEconomy previous = DenarEconomy.plugin;
    UUID id = UUID.randomUUID();
    try (var offline = mockStatic(OfflineModifier.class);
        var coins = mockConstruction(CoinLoader.class);
        var drops = mockConstruction(DropLoader.class);
        var messages = mockConstruction(MessageLoader.class)) {
      ServerMock server = MockBukkit.mock();
      FakeIdentity identity = FakeIdentity.register();
      identity.heirsReady = true;
      Player online = server.addPlayer();
      id = online.getUniqueId();
      UUID playerId = id;
      CharacterRef heir = identity.character(id);
      identity.heirs.put(id, heir);
      Path legacy =
          server
              .getPluginManager()
              .getParentTemporaryDirectory()
              .toPath()
              .resolve("DenarEconomy-test")
              .resolve("PlayerData");
      Files.createDirectories(legacy);
      Files.writeString(
          legacy.resolve(id + ".json"),
          "{\"pouch\":{\"amount\":2.5},\"bank\":{\"amount\":3}}");
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
      assertTrue(new File(plugin.getDataFolder(), "ledger.db").isFile());
      assertTrue(DenarEconomy.getLedger().isOpen());
      assertInstanceOf(
          net.tfminecraft.denareconomy.ledger.MoneyEstates.class,
          Bukkit.getServicesManager()
              .load(net.tfminecraft.tlibs.character.estate.EstateParticipant.class));
      assertTrue(new File(plugin.getDataFolder(), "PlayerData.imported").isDirectory());
      assertEquals(250, Denars.balance(Denars.pouch(heir)));
      assertEquals(300, Denars.balance(Denars.bank(heir)));
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
      ArmorStand live = mock(ArmorStand.class);
      ArmorStand dead = mock(ArmorStand.class);
      when(dead.isDead()).thenReturn(true);
      var money = DenarEconomy.getMoneyManager();
      money.getStandMap().put(id, live);
      money.getStandMap().put(UUID.randomUUID(), dead);
      money.getStandMap().put(UUID.randomUUID(), null);
      money.getTaskMap().put(id, 17);
      BukkitScheduler scheduler = mock(BukkitScheduler.class);
      Ledger ledger = DenarEconomy.getLedger();
      try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        plugin.onDisable();
        assertFalse(ledger.isOpen());
        verify(live).remove();
        verify(dead, never()).remove();
        verify(scheduler).cancelTask(17);
        assertTrue(money.getStandMap().isEmpty());
        assertTrue(money.getTaskMap().isEmpty());
        plugin.onDisable();
        verifyNoMoreInteractions(scheduler);
      }
    } finally {
      MockBukkit.unmock();
      DenarEconomy.getMoneyManager().getStandMap().clear();
      DenarEconomy.getMoneyManager().getTaskMap().clear();
      DenarEconomy.setLedger(Ledger.unavailable());
      DenarEconomy.plugin = previous;
    }
  }
}
