package net.tfminecraft.denareconomy.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import net.tfminecraft.denareconomy.accounts.OfflineModifier;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.database.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class PlayerManagerTest {
  @Test
  void lazilyCreatesAndCachesNewPlayers() {
    try (MockedStatic<Database> database = mockStatic(Database.class)) {
      PlayerManager manager = new PlayerManager();
      UUID id = UUID.randomUUID();
      Player player = player(id);
      assertFalse(manager.exists(player));
      assertNull(manager.peek(id));
      PlayerData data = manager.get(player);
      assertEquals(id, data.getId());
      assertTrue(manager.exists(id));
      assertTrue(manager.exists(player));
      assertSame(data, manager.get(id));
      manager.add(id);
      manager.init(player);
      assertSame(data, manager.peek(id));
      database.verify(() -> Database.hasPlayerData(id), times(1));
      database.verifyNoMoreInteractions();
    }
  }

  @Test
  void loadsExistingDataAndSavesOnlyCachedPlayers() {
    try (MockedStatic<Database> database = mockStatic(Database.class)) {
      PlayerManager manager = new PlayerManager();
      UUID id = UUID.randomUUID();
      PlayerData saved = new PlayerData(id);
      saved.getBank().change(42);
      database.when(() -> Database.hasPlayerData(id)).thenReturn(true);
      database.when(() -> Database.loadPlayerData(id)).thenReturn(saved);
      manager.init(player(id));
      assertSame(saved, manager.get(id));
      manager.save(id);
      assertFalse(manager.exists(id));
      manager.save(id);
      database.verify(() -> Database.savePlayerData(saved), times(1));
    }
  }

  @Test
  void keepAndDropHandleInvalidInputsWithoutSaving() {
    try (MockedStatic<Database> database = mockStatic(Database.class)) {
      PlayerManager manager = new PlayerManager();
      UUID id = UUID.randomUUID();
      PlayerData data = new PlayerData(id);
      manager.keep(null);
      manager.keep(new PlayerData(null));
      assertFalse(manager.exists((UUID) null));
      manager.keep(data);
      assertSame(data, manager.peek(id));
      manager.drop(null);
      assertSame(data, manager.peek(id));
      manager.drop(id);
      assertNull(manager.peek(id));
      database.verifyNoInteractions();
    }
  }

  @Test
  void startsAllOnlinePlayersAndHandlesEmptyServer() {
    try (MockedStatic<Database> database = mockStatic(Database.class);
        MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      PlayerManager manager = new PlayerManager();
      Player first = player(UUID.randomUUID());
      Player second = player(UUID.randomUUID());
      bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(first, second));
      manager.start();
      assertTrue(manager.exists(first));
      assertTrue(manager.exists(second));
      bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
      manager.start();
      assertTrue(manager.exists(first));
    }
  }

  @Test
  void joinRemembersNameAndQuitPersistsAndRemovesSession() {
    try (MockedStatic<Database> database = mockStatic(Database.class);
        MockedStatic<OfflineModifier> names = mockStatic(OfflineModifier.class)) {
      PlayerManager manager = new PlayerManager();
      UUID id = UUID.randomUUID();
      Player player = player(id);
      when(player.getName()).thenReturn("Alex");
      PlayerJoinEvent join = mock(PlayerJoinEvent.class);
      when(join.getPlayer()).thenReturn(player);
      manager.onJoin(join);
      PlayerData data = manager.peek(id);
      assertNotNull(data);
      names.verify(() -> OfflineModifier.remember("Alex", id));
      PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
      when(quit.getPlayer()).thenReturn(player);
      manager.onQuit(quit);
      database.verify(() -> Database.savePlayerData(data));
      assertNull(manager.peek(id));
    }
  }

  private static Player player(UUID id) {
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(id);
    return player;
  }
}
