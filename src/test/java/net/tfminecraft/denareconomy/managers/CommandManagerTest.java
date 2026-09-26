package net.tfminecraft.denareconomy.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.database.BalTopEntry;
import net.tfminecraft.denareconomy.database.Database;
import net.tfminecraft.denareconomy.event.PlayerBankPulseEvent;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.loaders.CoinLoader;
import net.tfminecraft.denareconomy.loaders.MessageLoader;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class CommandManagerTest {
  private final CommandManager commands = new CommandManager();
  private Player player;
  private Command deco;
  private MoneyManager money;
  private PlayerData data;
  private PluginManager events;
  private MockedStatic<DenarEconomy> economy;
  private MockedStatic<MessageLoader> messages;
  private MockedStatic<Bukkit> bukkit;
  private MockedStatic<CoinLoader> coins;
  private DenarEconomy previousPlugin;

  @BeforeEach
  void setUp() {
    player = mock(Player.class);
    deco = command("deco");
    money = mock(MoneyManager.class);
    data = new PlayerData(UUID.randomUUID());
    PlayerManager players = mock(PlayerManager.class);
    when(players.get(player)).thenReturn(data);
    economy = mockStatic(DenarEconomy.class);
    economy.when(DenarEconomy::getPlayerManager).thenReturn(players);
    economy.when(DenarEconomy::getMoneyManager).thenReturn(money);
    messages = mockStatic(MessageLoader.class);
    bukkit = mockStatic(Bukkit.class);
    events = mock(PluginManager.class);
    bukkit.when(Bukkit::getPluginManager).thenReturn(events);
    coins = mockStatic(CoinLoader.class);
    previousPlugin = DenarEconomy.plugin;
  }

  @AfterEach
  void tearDown() {
    DenarEconomy.plugin = previousPlugin;
    coins.close();
    bukkit.close();
    messages.close();
    economy.close();
  }

  @Test
  void dispatchRejectsConsoleAndUnknownCommandsAndOpensPouch() {
    CommandSender console = mock(CommandSender.class);
    assertFalse(commands.onCommand(console, deco, "deco", new String[0]));
    messages.verify(() -> MessageLoader.send(console, "general.players-only"));
    assertFalse(run());
    assertTrue(run("unknown"));
    messages.verify(() -> MessageLoader.send(player, "general.unknown-subcommand"), times(2));
    assertFalse(commands.onCommand(player, command("other"), "other", new String[] {"bal"}));
    assertTrue(commands.onCommand(player, command("POUCH"), "pouch", new String[0]));
    verify(money).showPouch(player);
  }

  @Test
  void reloadAllowsConsoleOperatorsAndPermissionButRejectsUnprivilegedPlayers() {
    DenarEconomy.plugin = mock(DenarEconomy.class);
    assertTrue(run("reload"));
    messages.verify(() -> MessageLoader.send(player, "errors.no-permission"));
    verify(DenarEconomy.plugin, never()).loadConfigs();
    when(player.hasPermission("denareconomy.reload")).thenReturn(true);
    assertTrue(run("ReLoAd"));
    when(player.hasPermission("denareconomy.reload")).thenReturn(false);
    when(player.isOp()).thenReturn(true);
    assertTrue(run("reload"));
    CommandSender console = mock(CommandSender.class);
    assertTrue(commands.onCommand(console, deco, "deco", new String[] {"reload"}));
    verify(DenarEconomy.plugin, times(3)).loadConfigs();
    messages.verify(() -> MessageLoader.send(console, "general.reload-ok"));
  }

  @Test
  void balanceDisplaysBothAccountsAndRankingDisplaysSortedDatabaseResults() {
    data.getPouch().change(10);
    data.getBank().change(20);
    assertTrue(run("bal"));
    messages.verify(() -> MessageLoader.send(player, "balance.pouch", "amount", 10.0));
    messages.verify(() -> MessageLoader.send(player, "balance.bank", "amount", 20.0));
    try (MockedStatic<Database> database = mockStatic(Database.class)) {
      database
          .when(() -> Database.getTopBalances(20))
          .thenReturn(List.of(new BalTopEntry("Alex", 30), new BalTopEntry("Sam", 12.5)));
      assertTrue(run("baltop"));
      messages.verify(() -> MessageLoader.send(player, "baltop.header", "limit", 20));
      messages.verify(
          () ->
              MessageLoader.send(
                  player,
                  "baltop.entry",
                  "rank",
                  1,
                  "name",
                  "Alex",
                  "amount",
                  String.format("%.2f", 30.0)));
      messages.verify(
          () ->
              MessageLoader.send(
                  player,
                  "baltop.entry",
                  "rank",
                  2,
                  "name",
                  "Sam",
                  "amount",
                  String.format("%.2f", 12.5)));
      messages.verify(() -> MessageLoader.send(player, "baltop.footer"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"pay", "toitem", "deposit", "withdraw"})
  void amountCommandsRejectMissingAndInvalidAmounts(String command) {
    run(command);
    messages.verify(() -> MessageLoader.send(player, "errors.no-amount"));
    run(command, "invalid");
    run(command, "0");
    run(command, "-1");
    messages.verify(() -> MessageLoader.send(player, "errors.invalid-amount"), times(3));
    assertEquals(0, data.getPouch().getBal());
    assertEquals(0, data.getBank().getBal());
    verifyNoInteractions(money);
  }

  @Test
  void payDelegatesValidatedAmount() {
    assertTrue(run("pay", "12.5"));
    verify(money).pay(player, 12.5);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.005", "0.015", "0.010000000000000001", "NaN", "Infinity", "1e1000"})
  void coinCommandsRejectFractionalCentsAndNonFiniteAmounts(String amount) {
    data.getPouch().setBal(5);
    run("pay", amount);
    run("toitem", amount);
    assertEquals(5, data.getPouch().getBal());
    verifyNoInteractions(money);
    messages.verify(() -> MessageLoader.send(player, "errors.invalid-amount"), times(2));
  }

  @Test
  void emptyItemConversionLeavesPouchUntouched() {
    data.getPouch().setBal(5);
    when(money.amountToItems(3, 0, .01)).thenReturn(List.of());
    run("toitem", "3");
    assertEquals(5, data.getPouch().getBal());
    messages.verify(() -> MessageLoader.send(player, "errors.coins-unavailable"));
    verify(player, never()).getInventory();
  }

  @Test
  void emptyNamedCoinConversionLeavesPouchUntouched() {
    Coin coin = coin("gold", 2.5, true);
    coins.when(() -> CoinLoader.getByString("gold")).thenReturn(coin);
    when(money.coinItems(coin, 2)).thenReturn(List.of());
    data.getPouch().setBal(5);
    run("toitem", "2", "gold");
    assertEquals(5, data.getPouch().getBal());
    messages.verify(() -> MessageLoader.send(player, "errors.coins-unavailable"));
    verify(player, never()).getInventory();
  }

  @Test
  void toItemRejectsUnknownNonwithdrawableAndInvalidCoinCounts() {
    Coin blocked = coin("blocked", 2, false);
    coins.when(() -> CoinLoader.getByString("blocked")).thenReturn(blocked);
    run("toitem", "1", "blocked");
    run("toitem", "1", "missing");
    run("toitem", "1", "-2");
    run("toitem", "1", "3");
    messages.verify(() -> MessageLoader.send(player, "errors.unknown-coin"), times(4));
    Coin valid = coin("gold", 2, true);
    coins.when(() -> CoinLoader.getByString("gold")).thenReturn(valid);
    run("toitem", "1.5", "gold");
    messages.verify(() -> MessageLoader.send(player, "errors.invalid-count"));
    verifyNoInteractions(money);
  }

  @Test
  void toItemChecksCostAndGivesOrDropsItems() {
    ItemStack first = mock(ItemStack.class);
    ItemStack second = mock(ItemStack.class);
    when(money.amountToItems(5, 0, .01)).thenReturn(List.of(first, second));
    run("toitem", "5");
    messages.verify(() -> MessageLoader.send(player, "errors.not-enough-pouch"));
    data.getPouch().change(10);
    PlayerInventory inventory = mock(PlayerInventory.class);
    World world = mock(World.class);
    when(player.getInventory()).thenReturn(inventory);
    when(player.getWorld()).thenReturn(world);
    when(inventory.addItem(first)).thenReturn(new java.util.HashMap<>());
    when(inventory.addItem(second))
        .thenReturn(new java.util.HashMap<>(java.util.Map.of(0, second)));
    run("toitem", "5");
    assertEquals(5, data.getPouch().getBal());
    verify(inventory).addItem(first);
    verify(world).dropItem(player.getLocation(), second);
  }

  @ParameterizedTest
  @ValueSource(strings = {"gold", "2.5"})
  void toItemResolvesCoinByIdOrValueAndChargesCount(String token) {
    Coin coin = coin("gold", 2.5, true);
    coins.when(() -> CoinLoader.getByString("gold")).thenReturn(coin);
    coins.when(() -> CoinLoader.getWithdrawableByValue(2.5)).thenReturn(coin);
    ItemStack payout = mock(ItemStack.class);
    when(money.coinItems(coin, 2)).thenReturn(List.of(payout));
    PlayerInventory inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    data.getPouch().change(5);
    run("toitem", "2", token);
    assertEquals(0, data.getPouch().getBal());
    verify(money).coinItems(coin, 2);
    verify(inventory).addItem(payout);
  }

  @ParameterizedTest
  @ValueSource(strings = {"deposit", "withdraw"})
  void cancelledBankPulseDoesNotMoveMoneyOrValidateAmounts(String command) {
    doAnswer(
            invocation -> {
              ((PlayerBankPulseEvent) invocation.getArgument(0)).setCancelled(true);
              return null;
            })
        .when(events)
        .callEvent(any(PlayerBankPulseEvent.class));
    run(command);
    verify(events).callEvent(any(PlayerBankPulseEvent.class));
    messages.verifyNoInteractions();
  }

  @ParameterizedTest
  @ValueSource(strings = {"deposit", "withdraw"})
  void bankTransfersAreAtomicAndReportNewBalances(String command) {
    boolean deposit = command.equals("deposit");
    run(command, "4.25");
    String error = deposit ? "errors.not-enough-pouch" : "errors.not-enough-bank";
    messages.verify(() -> MessageLoader.send(player, error));
    data.getPouch().change(10);
    data.getBank().change(10);
    String actionKey = deposit ? "bank.deposited" : "bank.withdrew";
    messages.when(() -> MessageLoader.get(actionKey)).thenReturn("Action");
    run(command, "4.25");
    assertEquals(deposit ? 5.75 : 14.25, data.getPouch().getBal());
    assertEquals(deposit ? 14.25 : 5.75, data.getBank().getBal());
    messages.verify(() -> MessageLoader.send(player, "bank.header"));
    messages.verify(
        () ->
            MessageLoader.send(
                player, "bank.action", "action", "Action", "amount", new BigDecimal("4.25")));
    messages.verify(
        () -> MessageLoader.send(player, "bank.new-bank", "amount", data.getBank().getBal()));
    messages.verify(
        () -> MessageLoader.send(player, "bank.new-pouch", "amount", data.getPouch().getBal()));
    messages.verify(() -> MessageLoader.send(player, "bank.footer"));
    verify(player).playSound(player, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1f);
  }

  @Test
  void completionsFilterCaseInsensitivelyAndRespectReloadPermission() {
    assertEquals(List.of("bal", "pay", "toitem", "deposit", "withdraw", "baltop"), tab(""));
    assertEquals(List.of("bal", "baltop"), tab("BA"));
    assertEquals(List.of(), tab("re"));
    when(player.hasPermission("denareconomy.reload")).thenReturn(true);
    assertEquals(List.of("reload"), tab("re"));
    assertTrue(
        commands
            .onTabComplete(mock(CommandSender.class), deco, "deco", new String[] {""})
            .contains("reload"));
    for (String name : List.of("pay", "toitem", "deposit", "withdraw")) {
      assertEquals(List.of("<amount>"), tab(name, ""));
    }
    assertEquals(List.of(), tab("bal", ""));
    assertEquals(List.of(), tab("pay", "", ""));
    assertEquals(List.of(), tab("toitem", "", "", ""));
    assertEquals(List.of(), tab());
    Coin gold = coin("gold", 2, true);
    Coin blocked = coin("blocked", 1, false);
    coins.when(CoinLoader::get).thenReturn(List.of(gold, blocked));
    assertEquals(List.of("gold"), tab("toitem", "1", "G"));
    assertNull(commands.onTabComplete(player, command("pouch"), "pouch", new String[] {""}));
    assertEquals(
        List.of(), commands.onTabComplete(player, command("other"), "other", new String[] {""}));
  }

  private boolean run(String... args) {
    return commands.onCommand(player, deco, "deco", args);
  }

  private List<String> tab(String... args) {
    return commands.onTabComplete(player, deco, "deco", args);
  }

  private static Command command(String name) {
    Command command = mock(Command.class);
    when(command.getName()).thenReturn(name);
    return command;
  }

  private static Coin coin(String id, double value, boolean withdraw) {
    org.bukkit.configuration.file.YamlConfiguration config =
        new org.bukkit.configuration.file.YamlConfiguration();
    config.set("item", "GOLD_NUGGET");
    config.set("value", value);
    config.set("withdraw", withdraw);
    return new Coin(id, config);
  }
}
