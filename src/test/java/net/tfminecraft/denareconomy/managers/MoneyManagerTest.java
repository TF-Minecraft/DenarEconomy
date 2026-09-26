package net.tfminecraft.denareconomy.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.Indyuce.mmoitems.MMOItems;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.accounts.OfflineModifier;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.database.Database;
import net.tfminecraft.denareconomy.drop.Drop;
import net.tfminecraft.denareconomy.enums.Accounts;
import net.tfminecraft.denareconomy.event.*;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.loaders.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class MoneyManagerTest {
  MoneyManager money;
  Player player;
  UUID id;
  PlayerData data;
  DenarEconomy previous;
  List<Coin> previousCoins;
  Map<Material, Drop> previousDrops;
  MockedStatic<MessageLoader> messages;

  @BeforeEach
  void setup() {
    MockBukkit.mock();
    new ItemStack(Material.GOLD_NUGGET); // Initialize Paper registries before static Bukkit mocks.
    previous = DenarEconomy.plugin;
    DenarEconomy.plugin = mock(DenarEconomy.class);
    when(DenarEconomy.plugin.getName()).thenReturn("DenarEconomy");
    when(DenarEconomy.plugin.namespace()).thenReturn("denareconomy");
    when(DenarEconomy.plugin.isEnabled()).thenReturn(true);
    player = mock(Player.class, RETURNS_DEEP_STUBS);
    id = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(id);
    when(player.getKiller()).thenReturn(null);
    when(player.getName()).thenReturn("Alex");
    when(player.isOnline()).thenReturn(true);
    data = new PlayerData(id);
    DenarEconomy.getPlayerManager().keep(data);
    money = spy(new MoneyManager());
    messages = mockStatic(MessageLoader.class);
    previousCoins = new ArrayList<>(CoinLoader.coins);
    previousDrops = new HashMap<>(DropLoader.drops);
    CoinLoader.coins.clear();
    DropLoader.drops.clear();
  }

  @AfterEach
  void cleanup() {
    messages.close();
    DenarEconomy.getPlayerManager().drop(id);
    CoinLoader.coins.clear();
    CoinLoader.coins.addAll(previousCoins);
    DropLoader.drops.clear();
    DropLoader.drops.putAll(previousDrops);
    MockBukkit.unmock();
    DenarEconomy.plugin = previous;
  }

  Coin coin(String item, double value, boolean withdraw) {
    Coin coin = mock(Coin.class);
    when(coin.getItem()).thenReturn(item);
    when(coin.getValue()).thenReturn(value);
    when(coin.canWithdraw()).thenReturn(withdraw);
    return coin;
  }

  org.bukkit.NamespacedKey key(String name) {
    return new NamespacedKey(DenarEconomy.plugin, name);
  }

  @Test
  void itemConversionRejectsUnrepresentableRemaindersWithoutCharging() {
    CoinLoader.coins.add(coin("v.gold_nugget", .1, true));
    data.getPouch().setBal(1);
    runConversion("0.15");
    assertEquals(1, data.getPouch().getBal());
    verify(player.getInventory(), never()).addItem(any(ItemStack[].class));
    messages.verify(() -> MessageLoader.send(player, "errors.coins-unavailable"));
  }

  @Test
  void missingPartOfAPayoutRejectsTheEntireConversion() {
    CoinLoader.coins.addAll(
        List.of(coin("m.material.absent", 1, true), coin("v.gold_nugget", .1, true)));
    data.getPouch().setBal(5);
    MMOItems previousMmoItems = MMOItems.plugin;
    try {
      MMOItems.plugin = mock(MMOItems.class);
      runConversion("1.10");
      assertEquals(5, data.getPouch().getBal());
      verify(player.getInventory(), never()).addItem(any(ItemStack[].class));
      messages.verify(() -> MessageLoader.send(player, "errors.coins-unavailable"));
      verify(MMOItems.plugin).getItem("MATERIAL", "ABSENT");
    } finally {
      MMOItems.plugin = previousMmoItems;
    }
  }

  @Test
  void unsupportedItemDefinitionsCannotProducePartialPayouts() {
    CoinLoader.coins.addAll(
        List.of(coin("unknown.coin", 1, true), coin("v.gold_nugget", .1, true)));
    assertTrue(money.amountToItems(1.1, 0, .01).isEmpty());
  }

  @Test
  void coinOverflowIsDroppedAndCombinedValueIsPreserved() {
    var inventory = new org.mockbukkit.mockbukkit.inventory.PlayerInventoryMock(player);
    when(player.getInventory()).thenReturn(inventory);
    // Occupy MockBukkit's equipment slots too: addItem otherwise treats them as storage.
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      if (slot != 35) inventory.setItem(slot, new ItemStack(Material.STONE, 64));
    }
    World world = mock(World.class);
    when(player.getWorld()).thenReturn(world);
    CoinLoader.coins.add(coin("v.gold_nugget", .01, true));
    data.getPouch().setBal(5);
    runConversion("1.28");

    var overflow = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
    Location dropLocation = player.getLocation();
    verify(world).dropItem(eq(dropLocation), overflow.capture());
    int received =
        Arrays.stream(inventory.getContents())
            .filter(item -> item != null && item.getType() == Material.GOLD_NUGGET)
            .mapToInt(ItemStack::getAmount)
            .sum();
    assertEquals(64, received);
    assertEquals(Material.GOLD_NUGGET, overflow.getValue().getType());
    assertEquals(128, received + overflow.getValue().getAmount());
    assertEquals(3.72, data.getPouch().getBal());
  }

  @Test
  void taxableEarningsWithoutATaxListenerAreCreditedInFull() {
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
      money.addMoney(player, 5, false, true);
      assertEquals(5, data.getPouch().getBal());
      messages.verify(() -> MessageLoader.send(player, "money.earned", "amount", 5.0));
      messages.verifyNoMoreInteractions();
    }
  }

  @Test
  void observingTheGrossAmountDoesNotImposeTax() {
    List<Double> observed = new ArrayList<>();
    Bukkit.getPluginManager()
        .registerEvent(
            PlayerEarnMoneyEvent.class,
            mock(org.bukkit.event.Listener.class),
            org.bukkit.event.EventPriority.NORMAL,
            (listener, event) -> observed.add(((PlayerEarnMoneyEvent) event).getAmount()),
            MockBukkit.createMockPlugin());
    assertEquals(0, money.doTaxes("Alex", 5));
    assertEquals(List.of(5.0), observed);
  }

  @Test
  void explicitFullTaxIsPreservedEvenWhenEqualToGross() {
    Bukkit.getPluginManager()
        .registerEvent(
            PlayerEarnMoneyEvent.class,
            mock(org.bukkit.event.Listener.class),
            org.bukkit.event.EventPriority.NORMAL,
            (listener, event) -> {
              PlayerEarnMoneyEvent earned = (PlayerEarnMoneyEvent) event;
              earned.setAmount(earned.getAmount());
            },
            MockBukkit.createMockPlugin());
    assertEquals(5, money.doTaxes("Alex", 5));
  }

  private void runConversion(String amount) {
    var command = mock(org.bukkit.command.Command.class);
    when(command.getName()).thenReturn("deco");
    assertTrue(
        new CommandManager().onCommand(player, command, "deco", new String[] {"toitem", amount}));
  }

  @Test
  void identifiesCoinsUsingConfiguredItemChecker() {
    ItemStack stack = new ItemStack(Material.GOLD_NUGGET, 3);
    Coin first = coin("v.diamond", 5, true), second = coin("v.gold_nugget", .1, true);
    CoinLoader.coins.addAll(List.of(first, second));
    try (var tlibs = mockStatic(TLibs.class)) {
      ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
      tlibs.when(TLibs::getItemAPI).thenReturn(api);
      when(api.getChecker().checkItemWithPath(stack, "v.gold_nugget")).thenReturn(true);
      assertSame(second, money.getCoin(stack));
      assertNull(money.getCoin(new ItemStack(Material.STONE)));
    }
    assertEquals(.3, money.combinedValue(second, stack));
  }

  @Test
  void createsVanillaAndMmoCoinsAndRejectsUnrepresentableAmounts() {
    Coin gold = coin("v.gold_nugget", 1, true), silver = coin("v.iron_nugget", .1, true);
    CoinLoader.coins.addAll(List.of(gold, silver));
    assertEquals(2, money.amountToItems(1.2).size());
    assertEquals(12, money.amountToItems(1.2, .1).getFirst().getAmount());
    assertTrue(money.amountToItems(.15, 1, .1).isEmpty());
    assertTrue(money.amountToItems(0).isEmpty());
    assertTrue(money.coinItems(null, 1).isEmpty());
    assertTrue(money.coinItems(gold, 0).isEmpty());
    assertTrue(money.coinItems(coin(null, 1, true), 1).isEmpty());
    assertTrue(money.coinItems(coin("unknown.coin", 1, true), 1).isEmpty());
    assertTrue(money.coinItems(gold, Long.MAX_VALUE).isEmpty());
    assertEquals(
        Integer.MAX_VALUE, money.coinItems(gold, Integer.MAX_VALUE).getFirst().getAmount());
    MMOItems old = MMOItems.plugin;
    try {
      MMOItems.plugin = mock(MMOItems.class);
      ItemStack stack = new ItemStack(Material.DIAMOND);
      when(MMOItems.plugin.getItem("MATERIAL", "COIN")).thenReturn(stack);
      assertSame(stack, money.coinItems(coin("m.material.coin", 1, true), 4).getFirst());
      assertEquals(4, stack.getAmount());
      assertTrue(money.coinItems(coin("m.material.absent", 1, true), 4).isEmpty());
    } finally {
      MMOItems.plugin = old;
    }
  }

  @Test
  void makesStrictlySmallerChangeAndRejectsMissingValues() {
    Coin gold = coin("v.gold_nugget", 1, true), silver = coin("v.iron_nugget", .1, true);
    CoinLoader.coins.addAll(List.of(gold, silver));
    ItemStack stack = new ItemStack(Material.GOLD_NUGGET);
    doReturn(gold).when(money).getCoin(stack);
    assertEquals(10, money.breakCoin(stack, 0).getFirst().getAmount());
    assertEquals(10, money.breakCoin(stack, .1).getFirst().getAmount());
    assertTrue(money.breakCoin(stack, 1).isEmpty());
    assertTrue(money.breakCoin(null, 0).isEmpty());
    ItemStack empty = mock(ItemStack.class);
    assertTrue(money.breakCoin(empty, 0).isEmpty());
    doReturn(null).when(money).getCoin(stack);
    assertTrue(money.breakCoin(stack, 0).isEmpty());
    when(gold.getValue()).thenReturn(null);
    doReturn(gold).when(money).getCoin(stack);
    assertTrue(money.breakCoin(stack, 0).isEmpty());
    // One null-valued denomination is valid input to the adapter (no comparator invocation).
    CoinLoader.coins.clear();
    CoinLoader.coins.add(gold);
    assertTrue(money.amountToItems(1).isEmpty());
  }

  @Test
  void rejectsChangeWhenDuplicateItemDefinitionsDisagreeOnValue() {
    Coin whole = coin("v.gold_nugget", 1, true);
    Coin half = coin("v.iron_nugget", .5, true);
    Coin conflicting = coin("v.iron_nugget", .25, true);
    CoinLoader.coins.addAll(List.of(whole, half, conflicting));
    ItemStack stack = new ItemStack(Material.GOLD_NUGGET);
    doReturn(whole).when(money).getCoin(stack);
    assertTrue(money.breakCoin(stack, 0).isEmpty(), "Ambiguous item values must not create money");
  }

  @Test
  void bridgesBalancesAndRejectsInvalidLegacyIds() {
    try (var offline = mockStatic(OfflineModifier.class);
        var database = mockStatic(Database.class)) {
      offline.when(() -> OfflineModifier.balance(id, Accounts.BANK)).thenReturn(5.);
      database.when(() -> Database.getTotalAmount(Accounts.BANK)).thenReturn(20.);
      assertEquals(5, money.getBalance(Accounts.BANK, id));
      assertEquals(20, money.getServerBal(Accounts.BANK));
      money.changeBal(null, 1, Accounts.POUCH);
      money.changeBal("bad", 1, Accounts.POUCH);
      money.changeBal(id.toString(), 0, Accounts.POUCH);
      money.changeBal(id.toString(), 1, null);
      money.changeBal(id.toString(), -3, Accounts.POUCH);
      offline.verify(() -> OfflineModifier.change(id, Accounts.POUCH, -3));
      offline.verify(() -> OfflineModifier.balance(id, Accounts.BANK));
      offline.verifyNoMoreInteractions();
    }
  }

  @Test
  void earningsApplyTaxAndMessagingForOnlineAndOfflinePlayers() {
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
        var offline = mockStatic(OfflineModifier.class)) {
      bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
      PluginManager plugins = mock(PluginManager.class);
      bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
      doAnswer(
              inv -> {
                ((PlayerEarnMoneyEvent) inv.getArgument(0)).setAmount(1.234);
                return null;
              })
          .when(plugins)
          .callEvent(any());
      assertEquals(1.23, money.doTaxes("Alex", 10));
      money.addMoney(player, 10, false, true);
      offline.verify(() -> OfflineModifier.change(id, Accounts.POUCH, 8.77));
      messages.verify(() -> MessageLoader.send(player, "money.earned", "amount", 8.77));
      messages.verify(() -> MessageLoader.send(player, "money.tax", "tax", 1.23));
      money.addMoneyToAccount(null, 1, false, false, Accounts.POUCH);
      money.addMoney(player, 0, false, false);
      doAnswer(
              inv -> {
                ((PlayerEarnMoneyEvent) inv.getArgument(0)).setAmount(-1);
                return null;
              })
          .when(plugins)
          .callEvent(any());
      money.addMoney(player, 3, true, true);
      offline.verify(() -> OfflineModifier.change(id, Accounts.POUCH, 3.));
      OfflinePlayer cached = mock(OfflinePlayer.class);
      when(cached.getName()).thenReturn("Alex");
      bukkit.when(() -> Bukkit.getOfflinePlayer(id)).thenReturn(cached);
      when(player.isOnline()).thenReturn(false);
      money.addMoney(player, 2, true, false);
      bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(null);
      money.addMoneyToAccount(id.toString(), 4, false, false, Accounts.BANK);
      offline.verify(() -> OfflineModifier.change(id, Accounts.BANK, 4.));
    }
  }

  @Test
  void rejectsUnfundedPouchAmountsBeforeConstructingItems() {
    data.getPouch().setBal(5);
    money.pay(player, 6);
    assertEquals(5, data.getPouch().getBal());
    messages.verify(() -> MessageLoader.send(player, "errors.not-enough-pouch"));
    verify(money, never()).amountToItems(anyDouble());
  }

  @Test
  void emptyCoinPayoutPreservesPouchAndExplainsFailure() {
    data.getPouch().setBal(5);
    assertTrue(CoinLoader.get().isEmpty());
    var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      money.pay(player, 3);
      assertEquals(5, data.getPouch().getBal());
      verifyNoInteractions(scheduler);
      messages.verify(() -> MessageLoader.send(player, "errors.coins-unavailable"));
    }
  }

  @Test
  void missingMmoItemPreservesPouch() {
    data.getPouch().setBal(5);
    CoinLoader.coins.add(coin("m.material.absent", 1, true));
    MMOItems previousMmoItems = MMOItems.plugin;
    var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      MMOItems.plugin = mock(MMOItems.class);
      money.pay(player, 3);
      verify(MMOItems.plugin).getItem("MATERIAL", "ABSENT");
      assertEquals(5, data.getPouch().getBal());
      verifyNoInteractions(scheduler);
    } finally {
      MMOItems.plugin = previousMmoItems;
    }
  }

  @Test
  void coinConstructionFailurePreservesPouch() {
    data.getPouch().setBal(5);
    CoinLoader.coins.add(coin("v.invalid_material", 1, true));
    assertThrows(IllegalArgumentException.class, () -> money.pay(player, 3));
    assertEquals(5, data.getPouch().getBal());
  }

  @Test
  void spawnsAtEyeOrWorldLocationAndMarksSilentItems() {
    World world = mock(World.class);
    Location location = new Location(world, 1, 3, 2);
    when(player.getEyeLocation()).thenReturn(location.clone());
    when(player.getLocation()).thenReturn(location.clone());
    Item entity = mock(Item.class);
    when(world.dropItem(any(), any())).thenReturn(entity);
    ItemStack stack = new ItemStack(Material.GOLD_NUGGET);
    assertSame(entity, money.spawnMoney(player, null, stack, true));
    assertEquals(
        1,
        stack
            .getItemMeta()
            .getPersistentDataContainer()
            .get(key("silent"), PersistentDataType.INTEGER));
    verify(player).swingMainHand();
    verify(entity).setVelocity(any());
    clearInvocations(entity);
    money.spawnMoney(null, location, stack, false);
    verify(entity, never()).setVelocity(any());
    assertEquals(3, location.getY());
  }

  @Test
  void materialDepositOnlyFiresForMainHandRightClicksWithItems() {
    PlayerInventory inventory = player.getInventory();
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      PluginManager plugins = mock(PluginManager.class);
      bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
      for (Action action : Action.values())
        for (EquipmentSlot hand : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
          PlayerInteractEvent event = mock(PlayerInteractEvent.class);
          when(event.getAction()).thenReturn(action);
          when(event.getHand()).thenReturn(hand);
          when(event.getPlayer()).thenReturn(player);
          ItemStack diamond = new ItemStack(Material.DIAMOND);
          when(inventory.getItemInMainHand()).thenReturn(diamond);
          money.depositMaterials(event);
        }
      verify(plugins, times(2)).callEvent(isA(PlayerDepositMaterialsEvent.class));
      PlayerInteractEvent event = mock(PlayerInteractEvent.class);
      when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
      when(event.getHand()).thenReturn(EquipmentSlot.HAND);
      when(event.getPlayer()).thenReturn(player);
      when(player.getInventory().getItemInMainHand()).thenReturn(null);
      money.depositMaterials(event);
      ItemStack air = new ItemStack(Material.AIR);
      when(inventory.getItemInMainHand()).thenReturn(air);
      money.depositMaterials(event);
      verifyNoMoreInteractions(plugins);
    }
  }

  @Test
  void deathDropsPositivePouchOnlyForEligiblePvp() {
    doReturn(List.of(new ItemStack(Material.GOLD_NUGGET))).when(money).amountToItems(5);
    PlayerDeathEvent event = mock(PlayerDeathEvent.class);
    when(event.getEntity()).thenReturn(player);
    data.getPouch().setBal(5);
    money.onPlayerDeath(event);
    when(player.getKiller()).thenReturn(player);
    money.onPlayerDeath(event);
    when(player.getKiller()).thenReturn(mock(Player.class));
    when(event.getKeepInventory()).thenReturn(true);
    money.onPlayerDeath(event);
    when(event.getKeepInventory()).thenReturn(false);
    when(player.hasMetadata(PouchDeathPolicy.KEEP_POUCH_METADATA)).thenReturn(true);
    money.onPlayerDeath(event);
    when(player.hasMetadata(PouchDeathPolicy.KEEP_POUCH_METADATA)).thenReturn(false);
    data.getPouch().setBal(0);
    money.onPlayerDeath(event);
    data.getPouch().setBal(5);
    money.onPlayerDeath(event);
    assertEquals(0, data.getPouch().getBal());
    verify(money).amountToItems(5);
    // Missing session payload can occur if a persistence adapter returns null.
    try (var db = mockStatic(Database.class)) {
      DenarEconomy.getPlayerManager().drop(id);
      db.when(() -> Database.hasPlayerData(id)).thenReturn(true);
      money.onPlayerDeath(event);
    }
  }

  @Test
  void directPaymentsRejectAmountsThatCannotBeDebitedInWholeCents() {
    data.getPouch().setBal(5);
    CoinLoader.coins.add(coin("v.gold_nugget", .01, true));
    for (double amount : new double[] {.005, .015, -.01, 0, Double.NaN, Double.POSITIVE_INFINITY}) {
      money.pay(player, amount);
      assertEquals(5, data.getPouch().getBal());
    }
    verify(money, never()).amountToItems(anyDouble());
    messages.verify(() -> MessageLoader.send(player, "errors.invalid-amount"), times(6));
  }

  @Test
  void cancelledPickupPreservesTheItemAndDoesNotCreditMoney() throws Exception {
    Item item = mock(Item.class);
    EntityPickupItemEvent event = new EntityPickupItemEvent(player, item, 0);
    event.setCancelled(true);
    money.pickupCoin(event);
    assertTrue(event.isCancelled());
    verifyNoInteractions(item);
    verify(money, never()).addMoney(any(), anyDouble(), anyBoolean(), anyBoolean());
    assertTrue(
        MoneyManager.class
            .getMethod("pickupCoin", EntityPickupItemEvent.class)
            .getAnnotation(org.bukkit.event.EventHandler.class)
            .ignoreCancelled());
  }

  @Test
  void pickupHonorsCoinTypeChainsCustomValueAndSender() {
    doNothing().when(money).addMoney(any(), anyDouble(), anyBoolean(), anyBoolean());
    ItemStack stack = new ItemStack(Material.GOLD_NUGGET, 3);
    Item item = mock(Item.class);
    when(item.getItemStack()).thenReturn(stack);
    EntityPickupItemEvent event = new EntityPickupItemEvent(player, item, 0);
    doReturn(null).when(money).getCoin(any());
    event = new EntityPickupItemEvent(player, item, 0);
    money.pickupCoin(event);
    assertFalse(event.isCancelled());
    Coin coin = coin("v.gold_nugget", .1, false);
    doReturn(coin).when(money).getCoin(any());
    event = new EntityPickupItemEvent(player, item, 0);
    money.pickupCoin(event);
    assertFalse(event.isCancelled());
    when(coin.canWithdraw()).thenReturn(true);
    money.pickupCoin(new EntityPickupItemEvent(mock(Zombie.class), item, 0));
    verify(item, never()).remove();
    event = new EntityPickupItemEvent(player, item, 0);
    money.pickupCoin(event);
    assertTrue(event.isCancelled());
    verify(item).remove();
    verify(money).addMoney(player, .3, false, true);
    ItemMeta meta = stack.getItemMeta();
    meta.getPersistentDataContainer().set(key("chained"), PersistentDataType.STRING, id.toString());
    stack.setItemMeta(meta);
    clearInvocations(money, item);
    event = new EntityPickupItemEvent(player, item, 0);
    money.pickupCoin(event);
    verify(item).remove();
    verify(money, never()).addMoney(any(), anyDouble(), anyBoolean(), anyBoolean());
    meta.getPersistentDataContainer().remove(key("chained"));
    UUID absent = UUID.randomUUID();
    meta.getPersistentDataContainer()
        .set(key("chained_to"), PersistentDataType.STRING, id + ";" + absent);
    meta.getPersistentDataContainer().set(key("customValue"), PersistentDataType.DOUBLE, 7.);
    meta.getPersistentDataContainer().set(key("silent"), PersistentDataType.INTEGER, 1);
    meta.getPersistentDataContainer().set(key("sender"), PersistentDataType.STRING, id.toString());
    stack.setItemMeta(meta);
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      Entity chained = mock(Item.class);
      bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(chained);
      clearInvocations(item);
      event = new EntityPickupItemEvent(player, item, 0);
      money.pickupCoin(event);
      verify(item).remove();
      verify(chained).remove();
      verify(money).addMoney(player, 7, true, false);
      meta.getPersistentDataContainer()
          .set(key("sender"), PersistentDataType.STRING, absent.toString());
      stack.setItemMeta(meta);
      clearInvocations(item);
      event = new EntityPickupItemEvent(player, item, 0);
      money.pickupCoin(event);
      verify(item).remove();
      verify(money).addMoney(player, 7, true, true);
    }
  }

  @Test
  void spawnedCoinsHaveNamesPickupDelayAndUniqueStackMarker() {
    money.coinSpawn(new EntitySpawnEvent(mock(Zombie.class)));
    Item item = mock(Item.class);
    ItemStack stack = new ItemStack(Material.GOLD_NUGGET, 2);
    when(item.getItemStack()).thenReturn(stack);
    EntitySpawnEvent event = new EntitySpawnEvent(item);
    doReturn(null).when(money).getCoin(any());
    money.coinSpawn(event);
    Coin coin = coin("v.gold_nugget", 1, false);
    doReturn(coin).when(money).getCoin(any());
    money.coinSpawn(event);
    verify(item, never()).setPickupDelay(anyInt());
    when(coin.canWithdraw()).thenReturn(true);
    money.coinSpawn(event);
    verify(item).setCustomNameVisible(true);
    assertNotNull(
        stack
            .getItemMeta()
            .getPersistentDataContainer()
            .get(key("nonstack"), PersistentDataType.STRING));
    for (double value : new double[] {10, 0}) {
      clearInvocations(item);
      ItemMeta meta = stack.getItemMeta();
      meta.getPersistentDataContainer().set(key("customValue"), PersistentDataType.DOUBLE, value);
      stack.setItemMeta(meta);
      money.coinSpawn(event);
      verify(item).setPickupDelay(10);
      verify(item, times(value > 0 ? 1 : 0)).setCustomNameVisible(true);
    }
  }
}
