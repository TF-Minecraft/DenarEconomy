package net.tfminecraft.denareconomy.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.drop.Drop;
import net.tfminecraft.denareconomy.loaders.DropLoader;
import net.tfminecraft.denareconomy.loaders.MessageLoader;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;

class MoneyDropTest {
  private DenarEconomy previousPlugin;
  private Map<Material, Drop> previousDrops;
  private MoneyManager money;
  private World world;
  private Player player;
  private Location location;
  private final List<Item> entities = new ArrayList<>();
  private final List<ItemStack> spawned = new ArrayList<>();

  @BeforeEach
  void setup() {
    MockBukkit.mock();
    previousPlugin = DenarEconomy.plugin;
    DenarEconomy.plugin = mock(DenarEconomy.class);
    when(DenarEconomy.plugin.getName()).thenReturn("DenarEconomy");
    when(DenarEconomy.plugin.namespace()).thenReturn("denareconomy");
    previousDrops = new HashMap<>(DropLoader.drops);
    DropLoader.drops.clear();
    money = spy(new MoneyManager());
    world = mock(World.class);
    location = new Location(world, 2, 10, 4);
    player = mock(Player.class, RETURNS_DEEP_STUBS);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getEyeLocation()).thenReturn(location.clone());
    when(player.getLocation()).thenReturn(location.clone());
    when(world.dropItem(any(Location.class), any(ItemStack.class)))
        .thenAnswer(
            invocation -> {
              ItemStack stack = invocation.getArgument(1);
              Item entity = mock(Item.class);
              when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
              when(entity.getItemStack()).thenReturn(stack);
              spawned.add(stack);
              entities.add(entity);
              return entity;
            });
  }

  @AfterEach
  void cleanup() {
    DenarEconomy.getPlayerManager().drop(player.getUniqueId());
    DropLoader.drops.clear();
    DropLoader.drops.putAll(previousDrops);
    MockBukkit.unmock();
    DenarEconomy.plugin = previousPlugin;
  }

  @Test
  void deathPreservesPouchUntilCoinsAreAvailable() {
    PlayerData data = new PlayerData(player.getUniqueId());
    data.getPouch().setBal(5);
    DenarEconomy.getPlayerManager().keep(data);
    when(player.getKiller()).thenReturn(mock(Player.class));
    var event = mock(org.bukkit.event.entity.PlayerDeathEvent.class);
    when(event.getEntity()).thenReturn(player);
    doReturn(List.of()).when(money).amountToItems(5);

    assertTrue(schedule(() -> money.onPlayerDeath(event)).isEmpty());
    assertEquals(5, data.getPouch().getBal());

    doReturn(List.of(new ItemStack(Material.GOLD_NUGGET))).when(money).amountToItems(5);
    var tasks = schedule(() -> money.onPlayerDeath(event));
    assertEquals(0, data.getPouch().getBal());
    assertEquals(1, tasks.size());
    tasks.getFirst().runnable().run();
    assertEquals(5.0, metadata(spawned.getFirst(), "customValue", PersistentDataType.DOUBLE));
  }

  @Test
  void paymentPreparesCoinsOnceThenDebitsAndSchedulesThoseExactItems() {
    PlayerData data = new PlayerData(player.getUniqueId());
    data.getPouch().setBal(5);
    DenarEconomy.getPlayerManager().keep(data);
    ItemStack prepared = new ItemStack(Material.GOLD_NUGGET, 3);
    doAnswer(
            invocation -> {
              assertEquals(5, data.getPouch().getBal(), "Coin creation must precede the charge");
              return List.of(prepared);
            })
        .when(money)
        .amountToItems(3);

    var tasks = schedule(() -> money.pay(player, 3));
    assertEquals(2, data.getPouch().getBal());
    verify(money).amountToItems(3);
    assertEquals(1, tasks.size());
    assertTrue(spawned.isEmpty());
    tasks.getFirst().runnable().run();
    assertEquals(1, spawned.size());
    assertEquals(Material.GOLD_NUGGET, spawned.getFirst().getType());
    assertEquals(3, spawned.getFirst().getAmount());
    assertEquals(3.0, metadata(spawned.getFirst(), "customValue", PersistentDataType.DOUBLE));
    assertTrue(prepared.getItemMeta().getPersistentDataContainer().isEmpty());
  }

  @Test
  void emptyPayoutSchedulesNothing() {
    doReturn(List.of()).when(money).amountToItems(0);
    assertTrue(schedule(() -> money.dropItems(player, null, 0)).isEmpty());
    verify(world, never()).dropItem(any(Location.class), any(ItemStack.class));
  }

  @Test
  void missingFirstStackStillProducesSilentZeroValueLinkedCoins() {
    doReturn(List.of(new ItemStack(Material.GOLD_NUGGET), new ItemStack(Material.IRON_NUGGET)))
        .when(money)
        .amountToItems(2);
    var tasks = schedule(() -> money.dropItems(player, null, 2));
    // Fault injection: Bukkit promises a stack, but retain the existing adapter recovery.
    Item first = mock(Item.class);
    UUID firstId = UUID.randomUUID();
    when(first.getUniqueId()).thenReturn(firstId);
    doReturn(first).when(money).spawnMoney(eq(player), isNull(), any(), eq(false));
    tasks.forEach(task -> task.runnable().run());

    assertEquals(1, spawned.size());
    assertEquals(0.0, metadata(spawned.getFirst(), "customValue", PersistentDataType.DOUBLE));
    assertEquals(1, metadata(spawned.getFirst(), "silent", PersistentDataType.INTEGER));
    assertEquals(
        firstId.toString(), metadata(spawned.getFirst(), "chained", PersistentDataType.STRING));
    verify(first).getItemStack();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void missingFirstEntityIdStopsTheChainAndOnlyNotifiesAPlayer(boolean playerDrop) {
    Player sender = playerDrop ? player : null;
    Location origin = playerDrop ? null : location;
    doReturn(List.of(new ItemStack(Material.GOLD_NUGGET), new ItemStack(Material.IRON_NUGGET)))
        .when(money)
        .amountToItems(2);
    var tasks = schedule(() -> money.dropItems(sender, origin, 2));
    Item first = mock(Item.class);
    ItemStack firstStack = new ItemStack(Material.GOLD_NUGGET);
    when(first.getItemStack()).thenReturn(firstStack);
    // A broken adapter returning a null UUID must never spawn unlinked follow-up money.
    doReturn(first).when(money).spawnMoney(eq(sender), eq(origin), any(), eq(false));
    try (var messages = mockStatic(MessageLoader.class)) {
      tasks.forEach(task -> task.runnable().run());
      verify(money, never()).spawnMoney(any(), any(), any(), eq(true));
      assertTrue(spawned.isEmpty());
      assertTrue(firstStack.getItemMeta().getPersistentDataContainer().isEmpty());
      if (playerDrop) {
        messages.verify(() -> MessageLoader.send(player, "errors.item-chain-broken"));
      } else {
        messages.verifyNoInteractions();
      }
    }
  }

  @Test
  void playerPayoutIsStaggeredAndOnlyFirstCoinCarriesCombinedValue() {
    List<ItemStack> originals =
        List.of(
            new ItemStack(Material.GOLD_NUGGET, 3),
            new ItemStack(Material.IRON_NUGGET, 2),
            new ItemStack(Material.COPPER_INGOT));
    doReturn(originals).when(money).amountToItems(12.34);
    var tasks = schedule(() -> money.dropItems(player, null, 12.34));
    assertEquals(List.of(0L, 1L, 2L), tasks.stream().map(Scheduled::delay).toList());
    assertTrue(spawned.isEmpty(), "Entities should appear only when their scheduled task runs");
    tasks.forEach(task -> task.runnable().run());
    assertEquals(3, spawned.size());
    assertEquals(12.34, metadata(spawned.getFirst(), "customValue", PersistentDataType.DOUBLE));
    assertNull(metadata(spawned.getFirst(), "silent", PersistentDataType.INTEGER));
    assertNull(metadata(spawned.getFirst(), "chained", PersistentDataType.STRING));
    for (int index = 0; index < originals.size(); index++) {
      assertNotSame(originals.get(index), spawned.get(index));
      assertTrue(originals.get(index).getItemMeta().getPersistentDataContainer().isEmpty());
      assertEquals(originals.get(index).getAmount(), spawned.get(index).getAmount());
      assertEquals(
          player.getUniqueId().toString(),
          metadata(spawned.get(index), "sender", PersistentDataType.STRING));
      if (index > 0) {
        assertEquals(0.0, metadata(spawned.get(index), "customValue", PersistentDataType.DOUBLE));
        assertEquals(1, metadata(spawned.get(index), "silent", PersistentDataType.INTEGER));
        assertEquals(
            entities.getFirst().getUniqueId().toString(),
            metadata(spawned.get(index), "chained", PersistentDataType.STRING));
      }
    }
    assertEquals(
        entities.get(1).getUniqueId() + ";" + entities.get(2).getUniqueId(),
        metadata(spawned.getFirst(), "chained_to", PersistentDataType.STRING));
    verify(player, times(3)).swingMainHand();
  }

  @Test
  void worldPayoutUsesOneSharedLaunchVectorAndHasNoSender() {
    doReturn(List.of(new ItemStack(Material.GOLD_NUGGET), new ItemStack(Material.IRON_NUGGET)))
        .when(money)
        .amountToItems(2);
    schedule(() -> money.dropItems(null, location, 2)).forEach(task -> task.runnable().run());
    assertEquals(2, entities.size());
    ArgumentCaptor<Vector> velocity = ArgumentCaptor.forClass(Vector.class);
    verify(entities.getFirst()).setVelocity(velocity.capture());
    Vector vector = velocity.getValue();
    assertTrue(vector.getX() >= -0.1 && vector.getX() < 0.1);
    assertTrue(vector.getY() >= 0.2 && vector.getY() < 0.3);
    assertTrue(vector.getZ() >= -0.1 && vector.getZ() < 0.1);
    verify(entities.get(1)).setVelocity(vector);
    assertNull(metadata(spawned.getFirst(), "sender", PersistentDataType.STRING));
    assertEquals(2.0, metadata(spawned.getFirst(), "customValue", PersistentDataType.DOUBLE));
    assertEquals(10, location.getY(), "Dropping must not mutate the caller's location");
  }

  @Test
  void missingPlayerUnconfiguredBlocksAndYoungCropsScheduleNoReward() {
    Block block = mock(Block.class);
    when(block.getType()).thenReturn(Material.WHEAT);
    assertTrue(schedule(() -> money.breakBlock(new BlockBreakEvent(block, null))).isEmpty());
    assertTrue(schedule(() -> money.breakBlock(new BlockBreakEvent(block, player))).isEmpty());
    Drop drop = configuredDrop(Material.WHEAT, 1);
    Ageable crop = mock(Ageable.class);
    when(crop.getAge()).thenReturn(3);
    when(crop.getMaximumAge()).thenReturn(7);
    when(block.getBlockData()).thenReturn(crop);
    assertTrue(schedule(() -> money.breakBlock(new BlockBreakEvent(block, player))).isEmpty());
    verify(drop, never()).getAmount();
  }

  @Test
  void unchangedBlocksAndFailedRollsDoNotPayButRemovedBlocksPayAtTheirCenter() {
    Block block = block(Material.STONE);
    Drop drop = configuredDrop(Material.STONE, 1);
    doNothing().when(money).dropItems(any(), any(), anyDouble());
    when(player.getInventory().getItemInMainHand()).thenReturn(null);
    var unchanged = schedule(() -> money.breakBlock(new BlockBreakEvent(block, player)));
    assertEquals(5, unchanged.getFirst().delay());
    unchanged.getFirst().runnable().run();
    verify(money, never()).dropItems(any(), any(), anyDouble());

    when(drop.getChance()).thenReturn(0.0);
    var failedRoll = schedule(() -> money.breakBlock(new BlockBreakEvent(block, player)));
    when(block.getType()).thenReturn(Material.AIR);
    failedRoll.getFirst().runnable().run();
    verify(money, never()).dropItems(any(), any(), anyDouble());

    when(block.getType()).thenReturn(Material.STONE);
    when(drop.getChance()).thenReturn(1.0);
    when(player.getInventory().getItemInMainHand())
        .thenReturn(new ItemStack(Material.IRON_PICKAXE));
    var successful = schedule(() -> money.breakBlock(new BlockBreakEvent(block, player)));
    when(block.getType()).thenReturn(Material.AIR);
    successful.getFirst().runnable().run();
    verify(money).dropItems(null, new Location(world, 2.5, 10.1, 4.5), 2.5);
    assertEquals(10, location.getY());
  }

  @Test
  void matureCropsUseFortuneToolAndRewardOnlyAfterTheBlockChanges() {
    Block block = block(Material.WHEAT);
    configuredDrop(Material.WHEAT, 1);
    Ageable crop = mock(Ageable.class);
    when(crop.getAge()).thenReturn(7);
    when(crop.getMaximumAge()).thenReturn(7);
    when(block.getBlockData()).thenReturn(crop);
    ItemStack tool = spy(new ItemStack(Material.DIAMOND_HOE));
    tool.addUnsafeEnchantment(Enchantment.FORTUNE, 3);
    when(player.getInventory().getItemInMainHand()).thenReturn(tool);
    doNothing().when(money).dropItems(any(), any(), anyDouble());
    var tasks = schedule(() -> money.breakBlock(new BlockBreakEvent(block, player)));
    verify(tool).getEnchantmentLevel(Enchantment.FORTUNE);
    verify(money, never()).dropItems(any(), any(), anyDouble());
    when(block.getType()).thenReturn(Material.AIR);
    tasks.getFirst().runnable().run();
    verify(money).dropItems(null, new Location(world, 2.5, 10.1, 4.5), 2.5);
  }

  private Block block(Material material) {
    Block block = mock(Block.class);
    when(block.getType()).thenReturn(material);
    when(block.getLocation()).thenReturn(location);
    when(world.getBlockAt(any(Location.class))).thenReturn(block);
    return block;
  }

  private Drop configuredDrop(Material material, double chance) {
    Drop drop = mock(Drop.class);
    when(drop.getBlock()).thenReturn(material);
    when(drop.getChance()).thenReturn(chance);
    when(drop.getAmount()).thenReturn(2.5);
    DropLoader.drops.put(material, drop);
    return drop;
  }

  private List<Scheduled> schedule(Runnable action) {
    List<Scheduled> tasks = new ArrayList<>();
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(scheduler.runTaskLater(eq(DenarEconomy.plugin), any(Runnable.class), anyLong()))
        .thenAnswer(
            invocation -> {
              tasks.add(new Scheduled(invocation.getArgument(1), invocation.getArgument(2)));
              return mock(BukkitTask.class);
            });
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      action.run();
    }
    return tasks;
  }

  private <P, C> C metadata(ItemStack stack, String name, PersistentDataType<P, C> type) {
    return stack
        .getItemMeta()
        .getPersistentDataContainer()
        .get(new NamespacedKey(DenarEconomy.plugin, name), type);
  }

  private record Scheduled(Runnable runnable, long delay) {}
}
