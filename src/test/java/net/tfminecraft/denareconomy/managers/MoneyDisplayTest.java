package net.tfminecraft.denareconomy.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Consumer;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.loaders.MessageLoader;
import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class MoneyDisplayTest {
  private MoneyManager money;
  private Player player;
  private UUID id;
  private PlayerData data;
  private ArmorStand stand;
  private World world;
  private BukkitScheduler scheduler;
  private Runnable follow;
  private Runnable cleanup;
  private MockedStatic<Bukkit> bukkit;
  private MockedStatic<MessageLoader> messages;
  private DenarEconomy previous;

  @BeforeEach
  void setup() {
    previous = DenarEconomy.plugin;
    DenarEconomy.plugin = mock(DenarEconomy.class);
    player = mock(Player.class);
    id = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(id);
    when(player.isOnline()).thenReturn(true);
    data = new PlayerData(id);
    DenarEconomy.getPlayerManager().keep(data);
    money = spy(new MoneyManager());
    world = mock(World.class);
    when(player.getWorld()).thenReturn(world);
    when(player.getLocation()).thenAnswer(invocation -> new Location(world, 1, 2, 3));
    PlayerInventory inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    when(inventory.getContents()).thenReturn(new ItemStack[0]);
    stand = mock(ArmorStand.class);
    when(world.spawn(any(Location.class), eq(ArmorStand.class), any(Consumer.class)))
        .thenAnswer(
            invocation -> {
              Consumer<ArmorStand> configure = invocation.getArgument(2);
              configure.accept(stand);
              return stand;
            });
    scheduler = mock(BukkitScheduler.class);
    when(scheduler.scheduleSyncRepeatingTask(
            eq(DenarEconomy.plugin), any(Runnable.class), eq(0L), eq(2L)))
        .thenAnswer(
            invocation -> {
              follow = invocation.getArgument(1);
              return 42;
            });
    when(scheduler.runTaskLater(eq(DenarEconomy.plugin), any(Runnable.class), eq(100L)))
        .thenAnswer(
            invocation -> {
              cleanup = invocation.getArgument(1);
              return null;
            });
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    messages = mockStatic(MessageLoader.class);
  }

  @AfterEach
  void teardown() {
    messages.close();
    bukkit.close();
    DenarEconomy.getPlayerManager().drop(id);
    DenarEconomy.plugin = previous;
  }

  @Test
  void displaysPouchAndWithdrawableInventoryCoinsAndConfiguresTemporaryHologram() {
    data.getPouch().setBal(3);
    ItemStack air = stack(Material.AIR, 1);
    ItemStack ordinary = stack(Material.STONE, 1);
    ItemStack locked = stack(Material.IRON_NUGGET, 1);
    ItemStack gold = stack(Material.GOLD_NUGGET, 3);
    when(player.getInventory().getContents())
        .thenReturn(new ItemStack[] {null, air, ordinary, locked, gold});
    doReturn(null).when(money).getCoin(ordinary);
    Coin blocked = mock(Coin.class);
    doReturn(blocked).when(money).getCoin(locked);
    Coin coin = mock(Coin.class);
    when(coin.canWithdraw()).thenReturn(true);
    when(coin.getValue()).thenReturn(2.5);
    doReturn(coin).when(money).getCoin(gold);
    messages
        .when(() -> MessageLoader.get("pouch.hologram", "amount", String.format("%.2f", 10.5)))
        .thenReturn("10.50 Denars");
    money.showPouch(player);
    messages.verify(() -> MessageLoader.send(player, "pouch.showing", "amount", 10.5));
    verify(stand).setCustomName("10.50 Denars");
    verify(stand).setCustomNameVisible(true);
    verify(stand).setVisible(false);
    verify(stand).setMarker(true);
    verify(stand).setGravity(false);
    verify(stand).setSmall(true);
    verify(stand).setPersistent(false);
    verify(world)
        .spawn(eq(new Location(world, 1, 4.2, 3)), eq(ArmorStand.class), any(Consumer.class));
    assertSame(stand, money.getStandMap().get(id));
    assertEquals(42, money.getTaskMap().get(id));
    follow.run();
    verify(stand).teleport(new Location(world, 1, 4.2, 3));
    when(player.isOnline()).thenReturn(false);
    follow.run();
    when(player.isOnline()).thenReturn(true);
    when(stand.isDead()).thenReturn(true);
    follow.run();
    verify(stand, times(1)).teleport(any(Location.class));
  }

  @Test
  void cooldownRejectsRepeatedDisplaysAndAllowsExpiredDisplays() throws Exception {
    money.showPouch(player);
    money.showPouch(player);
    messages.verify(
        () ->
            MessageLoader.send(
                eq(player), eq("errors.pouch-cooldown"), eq("seconds"), anyString()));
    verify(world, times(1)).spawn(any(Location.class), eq(ArmorStand.class), any(Consumer.class));
    cooldown().put(id, 0L);
    money.showPouch(player);
    verify(stand).remove();
    verify(scheduler).cancelTask(42);
    verify(world, times(2)).spawn(any(Location.class), eq(ArmorStand.class), any(Consumer.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"alive", "dead", "null"})
  void replacesOldDisplayAndCancelsItsTask(String state) {
    ArmorStand old = state.equals("null") ? null : mock(ArmorStand.class);
    if (old != null) when(old.isDead()).thenReturn(state.equals("dead"));
    money.getStandMap().put(id, old);
    if (state.equals("alive")) money.getTaskMap().put(id, 17);
    money.showPouch(player);
    assertSame(stand, money.getStandMap().get(id));
    if (old != null) verify(old, times(state.equals("alive") ? 1 : 0)).remove();
    verify(scheduler, times(state.equals("alive") ? 1 : 0)).cancelTask(17);
  }

  @Test
  void expiryRemovesItsOwnDisplayAndTask() {
    money.showPouch(player);
    cleanup.run();
    assertFalse(money.getStandMap().containsKey(id));
    assertFalse(money.getTaskMap().containsKey(id));
    verify(scheduler).cancelTask(42);
    verify(stand).remove();
  }

  @Test
  void expiryToleratesMissingTaskAndAlreadyDeadStand() {
    money.showPouch(player);
    money.getTaskMap().clear();
    when(stand.isDead()).thenReturn(true);
    cleanup.run();
    assertTrue(money.getStandMap().isEmpty());
    verify(scheduler, never()).cancelTask(anyInt());
    verify(stand, never()).remove();
  }

  @Test
  void oldExpiryCannotRemoveReplacementEntryOrCancelReplacementTask() {
    money.showPouch(player);
    ArmorStand replacement = mock(ArmorStand.class);
    money.getStandMap().put(id, replacement);
    money.getTaskMap().put(id, 99);
    cleanup.run();
    assertSame(replacement, money.getStandMap().get(id));
    assertEquals(99, money.getTaskMap().get(id));
    verify(scheduler, never()).cancelTask(anyInt());
    verify(stand).remove();
    verifyNoInteractions(replacement);
  }

  @ParameterizedTest
  @ValueSource(strings = {"alive", "dead", "missing"})
  void quitClearsEntityTaskAndCooldown(String state) throws Exception {
    if (!state.equals("missing")) {
      money.getStandMap().put(id, stand);
      when(stand.isDead()).thenReturn(state.equals("dead"));
      money.getTaskMap().put(id, 42);
    }
    cooldown().put(id, Long.MAX_VALUE);
    PlayerQuitEvent event = mock(PlayerQuitEvent.class);
    when(event.getPlayer()).thenReturn(player);
    money.onQuitClearStand(event);
    assertTrue(money.getStandMap().isEmpty());
    assertTrue(money.getTaskMap().isEmpty());
    assertFalse(cooldown().containsKey(id));
    verify(stand, times(state.equals("alive") ? 1 : 0)).remove();
    verify(scheduler, times(state.equals("missing") ? 0 : 1)).cancelTask(42);
  }

  @Test
  void chunkUnloadOnlyRemovesMatchingDisplaysAndAvailableTasks() {
    Chunk unloaded = mock(Chunk.class);
    Chunk other = mock(Chunk.class);
    ArmorStand affected = locatedStand(unloaded);
    ArmorStand withoutTask = locatedStand(unloaded);
    ArmorStand unaffected = locatedStand(other);
    UUID noTask = UUID.randomUUID();
    UUID another = UUID.randomUUID();
    money.getStandMap().put(id, affected);
    money.getTaskMap().put(id, 42);
    money.getStandMap().put(noTask, withoutTask);
    money.getStandMap().put(another, unaffected);
    money.getStandMap().put(UUID.randomUUID(), null);
    ChunkUnloadEvent event = mock(ChunkUnloadEvent.class);
    when(event.getChunk()).thenReturn(unloaded);
    money.onChunkUnload(event);
    verify(affected).remove();
    verify(withoutTask).remove();
    verify(unaffected, never()).remove();
    verify(scheduler).cancelTask(42);
    assertFalse(money.getStandMap().containsKey(id));
    assertFalse(money.getStandMap().containsKey(noTask));
    assertSame(unaffected, money.getStandMap().get(another));
    assertEquals(2, money.getStandMap().size());
    assertTrue(money.getTaskMap().isEmpty());
  }

  @SuppressWarnings("unchecked")
  private Map<UUID, Long> cooldown() throws Exception {
    Field field = MoneyManager.class.getDeclaredField("pouchCooldown");
    field.setAccessible(true);
    return (Map<UUID, Long>) field.get(money);
  }

  private static ItemStack stack(Material material, int amount) {
    ItemStack stack = mock(ItemStack.class);
    when(stack.getType()).thenReturn(material);
    when(stack.getAmount()).thenReturn(amount);
    return stack;
  }

  private static ArmorStand locatedStand(Chunk chunk) {
    ArmorStand stand = mock(ArmorStand.class);
    Location location = mock(Location.class);
    when(stand.getLocation()).thenReturn(location);
    when(location.getChunk()).thenReturn(chunk);
    return stand;
  }
}
