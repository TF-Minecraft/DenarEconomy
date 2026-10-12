package net.tfminecraft.denareconomy.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import net.Indyuce.mmoitems.MMOItems;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Leg;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.denareconomy.api.TaxLeg;
import net.tfminecraft.denareconomy.api.TaxPolicy;
import net.tfminecraft.denareconomy.drop.Drop;
import net.tfminecraft.denareconomy.event.*;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.loaders.*;
import net.tfminecraft.denareconomy.testing.FakeIdentity;
import net.tfminecraft.denareconomy.testing.Money;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.character.CharacterRef;
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
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class MoneyManagerTest {
  @TempDir Path folder;
  MoneyManager money;
  Player player;
  UUID id;
  FakeIdentity identity;
  CharacterRef character;
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
    Money.open(folder);
    identity = FakeIdentity.register();
    character = identity.play(player);
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
    Money.close();
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

  long pouch() {
    return Denars.balance(Denars.pouch(character));
  }

  void fund(long cents) {
    Money.fund(Denars.pouch(character), cents);
  }

  /** Every taxable earning pays this share to a guild's holding account. */
  String taxAt(int percent) {
    String holding = Denars.guildTax(UUID.randomUUID());
    TaxPolicy policy = (earner, gross) -> Optional.of(new TaxLeg(holding, gross * percent / 100));
    Bukkit.getServicesManager()
        .register(TaxPolicy.class, policy, MockBukkit.createMockPlugin(), ServicePriority.Normal);
    return holding;
  }

  private void runConversion(String amount) {
    var command = mock(org.bukkit.command.Command.class);
    when(command.getName()).thenReturn("deco");
    try (var economy = mockStatic(DenarEconomy.class, CALLS_REAL_METHODS)) {
      economy.when(DenarEconomy::getMoneyManager).thenReturn(money);
      assertTrue(
          new CommandManager().onCommand(player, command, "deco", new String[] {"toitem", amount}));
    }
  }

  @Test
  void itemConversionRejectsUnrepresentableRemaindersWithoutCharging() {
    CoinLoader.coins.add(coin("v.gold_nugget", .1, true));
    fund(100);
    runConversion("0.15");
    assertEquals(100, pouch());
    verify(player.getInventory(), never()).addItem(any(ItemStack[].class));
    messages.verify(() -> MessageLoader.send(player, "errors.coins-unavailable"));
  }

  @Test
  void missingPartOfAPayoutRejectsTheEntireConversion() {
    CoinLoader.coins.addAll(
        List.of(coin("m.material.absent", 1, true), coin("v.gold_nugget", .1, true)));
    fund(500);
    MMOItems previousMmoItems = MMOItems.plugin;
    try {
      MMOItems.plugin = mock(MMOItems.class);
      runConversion("1.10");
      assertEquals(500, pouch());
      verify(player.getInventory(), never()).addItem(any(ItemStack[].class));
      messages.verify(() -> MessageLoader.send(player, "errors.coins-unavailable"));
      verify(MMOItems.plugin).getItem("MATERIAL", "ABSENT");
    } finally {
      MMOItems.plugin = previousMmoItems;
    }
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
    fund(500);
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
    assertEquals(372, pouch());
  }

  @Test
  void moneyNeedsAnOpenLedgerAndACurrentCharacter() {
    assertEquals(Optional.of(character), money.holder(player));
    identity.stop(player);
    assertEquals(Optional.empty(), money.holder(player));
    messages.verify(() -> MessageLoader.send(player, "errors.no-character"));
    Money.close();
    assertEquals(Optional.empty(), money.holder(player));
    messages.verify(() -> MessageLoader.send(player, "errors.ledger-unavailable"));
    MoneyManager.refusal(player, Result.REFUSED, "errors.not-enough-bank");
    MoneyManager.refusal(player, Result.CONFLICT, "errors.not-enough-bank");
    messages.verify(() -> MessageLoader.send(player, "errors.not-enough-bank"));
    messages.verify(() -> MessageLoader.send(player, "errors.ledger-unavailable"), times(2));
    assertEquals(new BigDecimal("12.34"), MoneyManager.denars(1234));
    assertTrue(MoneyManager.once("pay").startsWith("pay:"));
    assertNotEquals(MoneyManager.once("pay"), MoneyManager.once("pay"));
  }

  @Test
  void unfundedPaymentsLeaveThePouchAndDropNothing() {
    CoinLoader.coins.add(coin("v.gold_nugget", 1, true));
    fund(500);
    var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      money.pay(player, 600);
      assertEquals(500, pouch());
      verifyNoInteractions(scheduler);
      messages.verify(() -> MessageLoader.send(player, "errors.not-enough-pouch"));
      identity.stop(player);
      money.pay(player, 100);
      verifyNoInteractions(scheduler);
      messages.verify(() -> MessageLoader.send(player, "errors.no-character"));
    }
  }

  @Test
  void emptyCoinPayoutPreservesPouchAndExplainsFailure() {
    fund(500);
    assertTrue(CoinLoader.get().isEmpty());
    var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      money.pay(player, 300);
      assertEquals(500, pouch());
      verifyNoInteractions(scheduler);
      messages.verify(() -> MessageLoader.send(player, "errors.coins-unavailable"));
    }
  }

  @Test
  void missingMmoItemPreservesPouch() {
    fund(500);
    CoinLoader.coins.add(coin("m.material.absent", 1, true));
    MMOItems previousMmoItems = MMOItems.plugin;
    var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      MMOItems.plugin = mock(MMOItems.class);
      money.pay(player, 300);
      verify(MMOItems.plugin).getItem("MATERIAL", "ABSENT");
      assertEquals(500, pouch());
      verifyNoInteractions(scheduler);
    } finally {
      MMOItems.plugin = previousMmoItems;
    }
  }

  @Test
  void coinConstructionFailurePreservesPouch() {
    fund(500);
    CoinLoader.coins.add(coin("v.invalid_material", 1, true));
    assertThrows(IllegalArgumentException.class, () -> money.pay(player, 300));
    assertEquals(500, pouch());
  }

  @Test
  void materialDepositOnlyFiresForMainHandRightClicksWithItemsAndACharacter() {
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
      var fired = org.mockito.ArgumentCaptor.forClass(PlayerDepositMaterialsEvent.class);
      verify(plugins, times(2)).callEvent(fired.capture());
      assertEquals(character, fired.getValue().getCharacter());
      PlayerInteractEvent event = mock(PlayerInteractEvent.class);
      when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
      when(event.getHand()).thenReturn(EquipmentSlot.HAND);
      when(event.getPlayer()).thenReturn(player);
      when(player.getInventory().getItemInMainHand()).thenReturn(null);
      money.depositMaterials(event);
      ItemStack air = new ItemStack(Material.AIR);
      when(inventory.getItemInMainHand()).thenReturn(air);
      money.depositMaterials(event);
      when(inventory.getItemInMainHand()).thenReturn(new ItemStack(Material.DIAMOND));
      identity.stop(player);
      money.depositMaterials(event);
      verifyNoMoreInteractions(plugins);
    }
  }

  @Test
  void deathDropsPositivePouchOnlyForEligiblePvp() {
    doReturn(List.of(new ItemStack(Material.GOLD_NUGGET))).when(money).amountToItems(5);
    PlayerDeathEvent event = mock(PlayerDeathEvent.class);
    when(event.getEntity()).thenReturn(player);
    fund(500);
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
    identity.stop(player);
    money.onPlayerDeath(event);
    assertEquals(500, pouch());
    verify(money, never()).amountToItems(anyDouble());
    identity.start(player, character);
    var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      // A dead character's pouch is frozen for its estate.
      identity.kill(character);
      money.onPlayerDeath(event);
      assertEquals(500, pouch());
      verifyNoInteractions(scheduler);
      identity.characters.put(
          character.characterId(),
          new net.tfminecraft.tlibs.character.CharacterInfo(
              character, "Alex", net.tfminecraft.tlibs.character.CharacterStatus.ALIVE, null,
              Set.of(), 0, null));
      money.onPlayerDeath(event);
      assertEquals(0, pouch());
      verify(scheduler).runTaskLater(eq(DenarEconomy.plugin), any(Runnable.class), eq(0L));
      money.onPlayerDeath(event);
      verify(money, times(2)).amountToItems(5);
    }
  }

  @Test
  void cancelledPickupPreservesTheItemAndDoesNotCreditMoney() throws Exception {
    Item item = mock(Item.class);
    EntityPickupItemEvent event = new EntityPickupItemEvent(player, item, 0);
    event.setCancelled(true);
    money.pickupCoin(event);
    assertTrue(event.isCancelled());
    verifyNoInteractions(item);
    assertEquals(0, pouch());
    assertTrue(
        MoneyManager.class
            .getMethod("pickupCoin", EntityPickupItemEvent.class)
            .getAnnotation(org.bukkit.event.EventHandler.class)
            .ignoreCancelled());
  }

  Item coinEntity(ItemStack stack) {
    Item item = mock(Item.class);
    when(item.getItemStack()).thenReturn(stack);
    when(item.getUniqueId()).thenReturn(UUID.randomUUID());
    return item;
  }

  @Test
  void fullInventoryStillCollectsCoinsIntoThePouch() throws Exception {
    ItemStack stack = new ItemStack(Material.GOLD_NUGGET, 3);
    Item item = coinEntity(stack);
    Coin coin = coin("v.gold_nugget", .1, false);
    when(player.getCanPickupItems()).thenReturn(true);
    doReturn(null).when(money).getCoin(any());
    PlayerAttemptPickupItemEvent event = new PlayerAttemptPickupItemEvent(player, item, 3);
    money.pickupCoinWithFullInventory(event);
    assertFalse(event.isCancelled());
    doReturn(coin).when(money).getCoin(any());
    money.pickupCoinWithFullInventory(event);
    assertFalse(event.isCancelled());
    when(coin.canWithdraw()).thenReturn(true);
    // Room for some of the stack: EntityPickupItemEvent follows and takes the coins.
    event = new PlayerAttemptPickupItemEvent(player, item, 2);
    money.pickupCoinWithFullInventory(event);
    assertFalse(event.isCancelled());
    verify(item, never()).remove();
    event = new PlayerAttemptPickupItemEvent(player, item, 3);
    when(player.getCanPickupItems()).thenReturn(false);
    money.pickupCoinWithFullInventory(event);
    assertFalse(event.isCancelled());
    verify(item, never()).remove();
    when(player.getCanPickupItems()).thenReturn(true);
    money.pickupCoinWithFullInventory(event);
    assertTrue(event.isCancelled());
    verify(item).remove();
    assertEquals(30, pouch());
    messages.verify(() -> MessageLoader.send(player, "money.earned", "amount", new BigDecimal("0.30")));
    assertTrue(
        MoneyManager.class
            .getMethod("pickupCoinWithFullInventory", PlayerAttemptPickupItemEvent.class)
            .getAnnotation(org.bukkit.event.EventHandler.class)
            .ignoreCancelled());
  }

  @Test
  void pickupHonorsCoinTypeChainsCustomValueAndSender() {
    String holding = taxAt(10);
    ItemStack stack = new ItemStack(Material.GOLD_NUGGET, 3);
    Item item = coinEntity(stack);
    doReturn(null).when(money).getCoin(any());
    EntityPickupItemEvent event = new EntityPickupItemEvent(player, item, 0);
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
    assertEquals(27, pouch());
    assertEquals(3, Denars.balance(holding));
    messages.verify(() -> MessageLoader.send(player, "money.earned", "amount", new BigDecimal("0.27")));
    messages.verify(() -> MessageLoader.send(player, "money.tax", "tax", new BigDecimal("0.03")));

    // The zero-value companions of a payout are simply removed.
    ItemStack companion = new ItemStack(Material.GOLD_NUGGET, 3);
    ItemMeta meta = companion.getItemMeta();
    meta.getPersistentDataContainer().set(key("chained"), PersistentDataType.STRING, id.toString());
    companion.setItemMeta(meta);
    Item companionItem = coinEntity(companion);
    money.pickupCoin(new EntityPickupItemEvent(player, companionItem, 0));
    verify(companionItem).remove();
    assertEquals(27, pouch());

    // The first coin of a payout carries its value and removes the rest of the chain.
    ItemStack first = new ItemStack(Material.GOLD_NUGGET, 1);
    meta = first.getItemMeta();
    UUID absent = UUID.randomUUID();
    meta.getPersistentDataContainer()
        .set(key("chained_to"), PersistentDataType.STRING, id + ";" + absent);
    meta.getPersistentDataContainer().set(key("customValue"), PersistentDataType.DOUBLE, 7.);
    meta.getPersistentDataContainer().set(key("silent"), PersistentDataType.INTEGER, 1);
    meta.getPersistentDataContainer()
        .set(key("sender"), PersistentDataType.STRING, character.characterId().toString());
    first.setItemMeta(meta);
    Item firstItem = coinEntity(first);
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      Entity chained = mock(Item.class);
      bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(chained);
      money.pickupCoin(new EntityPickupItemEvent(player, firstItem, 0));
      verify(firstItem).remove();
      verify(chained).remove();
    }
    assertEquals(727, pouch(), "The sender's own coins are untaxed");
    // Someone else's payout is taxed.
    meta.getPersistentDataContainer()
        .set(key("sender"), PersistentDataType.STRING, UUID.randomUUID().toString());
    meta.getPersistentDataContainer().remove(key("chained_to"));
    first.setItemMeta(meta);
    money.pickupCoin(new EntityPickupItemEvent(player, coinEntity(first), 0));
    assertEquals(1357, pouch());
    messages.verify(
        () -> MessageLoader.send(eq(player), eq("money.earned"), any(Object[].class)), times(1));
  }

  @Test
  void aPickedUpCoinIsCreditedOnceAndStaysOnTheGroundWhenItCannotBe() {
    Coin coin = coin("v.gold_nugget", .5, true);
    doReturn(coin).when(money).getCoin(any());
    ItemStack stack = new ItemStack(Material.GOLD_NUGGET, 2);
    Item item = coinEntity(stack);
    identity.stop(player);
    EntityPickupItemEvent event = new EntityPickupItemEvent(player, item, 0);
    money.pickupCoin(event);
    assertTrue(event.isCancelled(), "A player with no character cannot take coins");
    verify(item, never()).remove();
    identity.start(player, character);

    // The same entity reappearing after a crash is not credited again.
    money.pickupCoin(new EntityPickupItemEvent(player, item, 0));
    money.pickupCoin(new EntityPickupItemEvent(player, item, 0));
    verify(item, times(2)).remove();
    assertEquals(100, pouch());
    assertEquals(Optional.of(Result.APPLIED), Denars.status("coin:" + item.getUniqueId()));

    // Credited before under different tax: already paid for, so only removed.
    Item conflicting = coinEntity(stack);
    Denars.post(
        "coin:" + conflicting.getUniqueId(),
        "earn",
        List.of(new Leg(Denars.MINT, -1), new Leg(Denars.pouch(character), 1)));
    money.pickupCoin(new EntityPickupItemEvent(player, conflicting, 0));
    verify(conflicting).remove();
    assertEquals(101, pouch());

    // A worthless coin is removed without a posting.
    ItemStack worthless = new ItemStack(Material.GOLD_NUGGET, 1);
    ItemMeta meta = worthless.getItemMeta();
    meta.getPersistentDataContainer().set(key("customValue"), PersistentDataType.DOUBLE, 0.);
    worthless.setItemMeta(meta);
    Item zero = coinEntity(worthless);
    money.pickupCoin(new EntityPickupItemEvent(player, zero, 0));
    verify(zero).remove();
    assertEquals(Optional.empty(), Denars.status("coin:" + zero.getUniqueId()));

    // With the ledger closed the coin stays where it is.
    Money.close();
    Item later = coinEntity(stack);
    money.pickupCoin(new EntityPickupItemEvent(player, later, 0));
    verify(later, never()).remove();
  }

  @Test
  void heldCoinsMoveIntoThePouchWithoutTax() {
    taxAt(100);
    PlayerInventory inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    when(inventory.getItemInMainHand()).thenReturn(null);
    money.toPouch(player);
    when(inventory.getItemInMainHand()).thenReturn(new ItemStack(Material.AIR));
    money.toPouch(player);
    ItemStack stack = new ItemStack(Material.GOLD_NUGGET, 3);
    when(inventory.getItemInMainHand()).thenReturn(stack);
    doReturn(null).when(money).getCoin(stack);
    money.toPouch(player);
    Coin blocked = coin("v.gold_nugget", .1, false);
    doReturn(blocked).when(money).getCoin(stack);
    money.toPouch(player);
    messages.verify(() -> MessageLoader.send(player, "errors.no-coin-in-hand"), times(4));
    verify(inventory, never()).setItemInMainHand(any());

    Coin gold = coin("v.gold_nugget", .1, true);
    doReturn(gold).when(money).getCoin(stack);
    ItemMeta worldPayout = stack.getItemMeta();
    worldPayout.getPersistentDataContainer().set(key("worldPayout"), PersistentDataType.BYTE, (byte) 1);
    stack.setItemMeta(worldPayout);
    money.toPouch(player);
    messages.verify(() -> MessageLoader.send(player, "errors.world-payout"));
    assertEquals(0, pouch());
    worldPayout.getPersistentDataContainer().remove(key("worldPayout"));
    stack.setItemMeta(worldPayout);
    ItemMeta emptyValue = stack.getItemMeta();
    emptyValue.getPersistentDataContainer().set(key("customValue"), PersistentDataType.DOUBLE, 0.);
    stack.setItemMeta(emptyValue);
    money.toPouch(player);
    messages.verify(() -> MessageLoader.send(player, "errors.no-coin-in-hand"), times(5));
    assertEquals(0, pouch());

    ItemMeta priced = stack.getItemMeta();
    priced.getPersistentDataContainer().set(key("customValue"), PersistentDataType.DOUBLE, 4.5);
    stack.setItemMeta(priced);
    identity.stop(player);
    money.toPouch(player);
    messages.verify(() -> MessageLoader.send(player, "errors.no-character"));
    identity.start(player, character);
    money.toPouch(player);
    assertEquals(450, pouch());
    var order = inOrder(inventory, player);
    order.verify(inventory).setItemInMainHand(null);
    order.verify(player).saveData();
    verify(player).playSound(player, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1f);
    messages.verify(() -> MessageLoader.send(player, "money.earned", "amount", new BigDecimal("4.50")));

    ItemStack face = new ItemStack(Material.GOLD_NUGGET, 3);
    when(inventory.getItemInMainHand()).thenReturn(face);
    doReturn(gold).when(money).getCoin(face);
    money.toPouch(player);
    assertEquals(480, pouch());

    // A credit the pouch cannot hold leaves the coins in hand.
    fund(Long.MAX_VALUE - 480);
    money.toPouch(player);
    verify(inventory, times(2)).setItemInMainHand(null);
    messages.verify(() -> MessageLoader.send(player, "errors.ledger-unavailable"));
  }

  @Test
  void droppedCoinsAreUntaxedForTheDroppingCharacterAndTaxedForAnyoneElse() {
    ItemStack stack = new ItemStack(Material.GOLD_NUGGET, 2);
    Item dropped = coinEntity(stack);
    Coin coin = coin("v.gold_nugget", .5, true);
    doReturn(null).when(money).getCoin(stack);
    money.onPlayerDropCoin(new PlayerDropItemEvent(player, dropped));
    verify(dropped, never()).setItemStack(any());
    when(coin.canWithdraw()).thenReturn(false);
    doReturn(coin).when(money).getCoin(stack);
    money.onPlayerDropCoin(new PlayerDropItemEvent(player, dropped));
    verify(dropped, never()).setItemStack(any());
    when(coin.canWithdraw()).thenReturn(true);
    ItemMeta worldPayout = stack.getItemMeta();
    worldPayout.getPersistentDataContainer().set(key("worldPayout"), PersistentDataType.BYTE, (byte) 1);
    stack.setItemMeta(worldPayout);
    money.onPlayerDropCoin(new PlayerDropItemEvent(player, dropped));
    verify(dropped, never()).setItemStack(any());
    worldPayout.getPersistentDataContainer().remove(key("worldPayout"));
    stack.setItemMeta(worldPayout);
    identity.stop(player);
    money.onPlayerDropCoin(new PlayerDropItemEvent(player, dropped));
    verify(dropped, never()).setItemStack(any());
    identity.start(player, character);
    money.onPlayerDropCoin(new PlayerDropItemEvent(player, dropped));
    verify(dropped).setItemStack(stack);
    assertEquals(
        character.characterId().toString(),
        stack.getItemMeta().getPersistentDataContainer().get(key("sender"), PersistentDataType.STRING));

    taxAt(100);
    money.pickupCoin(new EntityPickupItemEvent(player, dropped, 0));
    assertEquals(100, pouch());
    // Another character of the same player is someone else.
    CharacterRef second = identity.character(id);
    identity.start(player, second);
    Item again = coinEntity(stack);
    money.pickupCoin(new EntityPickupItemEvent(player, again, 0));
    assertEquals(0, Denars.balance(Denars.pouch(second)));
    verify(again).remove();
  }

  @Test
  void unsupportedItemDefinitionsCannotProducePartialPayouts() {
    CoinLoader.coins.addAll(
        List.of(coin("unknown.coin", 1, true), coin("v.gold_nugget", .1, true)));
    assertTrue(money.amountToItems(1.1, 0, .01).isEmpty());
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
