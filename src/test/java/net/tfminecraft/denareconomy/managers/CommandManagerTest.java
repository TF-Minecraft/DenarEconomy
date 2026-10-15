package net.tfminecraft.denareconomy.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Leg;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.denareconomy.event.PlayerBankPulseEvent;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.ledger.AccountIds;
import net.tfminecraft.denareconomy.ledger.Ledger;
import net.tfminecraft.denareconomy.loaders.CoinLoader;
import net.tfminecraft.denareconomy.loaders.MessageLoader;
import net.tfminecraft.denareconomy.testing.FakeIdentity;
import net.tfminecraft.denareconomy.testing.Money;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

class CommandManagerTest {
  @TempDir Path folder;
  private final CommandManager commands = new CommandManager();
  private ServerMock server;
  private FakeIdentity identity;
  private PlayerMock player;
  private CharacterRef character;
  private Command deco;
  private MoneyManager money;
  private Logger logger;
  private final List<PlayerBankPulseEvent> pulses = new java.util.ArrayList<>();
  private MockedStatic<DenarEconomy> economy;
  private MockedStatic<MessageLoader> messages;
  private MockedStatic<CoinLoader> coins;
  private DenarEconomy previousPlugin;

  @BeforeEach
  void setUp() throws Exception {
    server = MockBukkit.mock();
    Money.open(folder);
    identity = FakeIdentity.register();
    player = server.addPlayer("Alex");
    character = identity.play(player);
    deco = command("deco");
    previousPlugin = DenarEconomy.plugin;
    DenarEconomy.plugin = mock(DenarEconomy.class);
    logger = mock(Logger.class);
    when(DenarEconomy.plugin.getLogger()).thenReturn(logger);
    when(DenarEconomy.plugin.getDataFolder()).thenReturn(folder.toFile());
    money = mock(MoneyManager.class);
    when(money.holder(any())).thenCallRealMethod();
    economy = mockStatic(DenarEconomy.class, CALLS_REAL_METHODS);
    economy.when(DenarEconomy::getMoneyManager).thenReturn(money);
    messages = mockStatic(MessageLoader.class);
    coins = mockStatic(CoinLoader.class);
    server
        .getPluginManager()
        .registerEvent(
            PlayerBankPulseEvent.class,
            new Listener() {},
            EventPriority.NORMAL,
            (listener, event) -> pulses.add((PlayerBankPulseEvent) event),
            MockBukkit.createMockPlugin());
  }

  @AfterEach
  void tearDown() {
    coins.close();
    messages.close();
    economy.close();
    Money.close();
    MockBukkit.unmock();
    DenarEconomy.plugin = previousPlugin;
  }

  long pouch() {
    return Denars.balance(Denars.pouch(character));
  }

  long bank() {
    return Denars.balance(Denars.bank(character));
  }

  @Test
  void dispatchRejectsConsoleAndUnknownCommandsAndOpensPouch() {
    CommandSender console = server.getConsoleSender();
    assertFalse(commands.onCommand(console, deco, "deco", new String[] {"bal"}));
    messages.verify(() -> MessageLoader.send(console, "general.players-only"));
    assertFalse(run());
    assertTrue(run("unknown"));
    messages.verify(() -> MessageLoader.send(player, "general.unknown-subcommand"), times(2));
    assertTrue(commands.onCommand(player, command("pouch"), "pouch", new String[0]));
    verify(money).showPouch(player);
    assertFalse(commands.onCommand(player, command("other"), "other", new String[] {"bal"}));
  }

  @Test
  void reloadAllowsConsoleOperatorsAndPermissionButRejectsUnprivilegedPlayers() {
    assertTrue(run("reload"));
    messages.verify(() -> MessageLoader.send(player, "errors.no-permission"));
    player.addAttachment(MockBukkit.createMockPlugin(), "denareconomy.reload", true);
    assertTrue(run("reload"));
    PlayerMock op = server.addPlayer("Op");
    op.setOp(true);
    assertTrue(commands.onCommand(op, deco, "deco", new String[] {"reload"}));
    CommandSender console = server.getConsoleSender();
    assertTrue(commands.onCommand(console, deco, "deco", new String[] {"reload"}));
    verify(DenarEconomy.plugin, times(3)).loadConfigs();
    messages.verify(() -> MessageLoader.send(console, "general.reload-ok"));
  }

  @Test
  void balanceShowsTheCurrentCharactersAccounts() {
    Money.fund(Denars.pouch(character), 1000);
    Money.fund(Denars.bank(character), 2050);
    assertTrue(run("bal"));
    messages.verify(
        () -> MessageLoader.send(player, "balance.pouch", "amount", new BigDecimal("10.00")));
    messages.verify(
        () -> MessageLoader.send(player, "balance.bank", "amount", new BigDecimal("20.50")));
  }

  @Test
  void moneyCommandsNeedACharacterAndAnOpenLedger() {
    identity.stop(player);
    run("bal");
    run("deposit", "1");
    run("toitem", "1");
    when(money.amountToItems(1, 0, .01)).thenReturn(List.of(new ItemStack(Material.GOLD_NUGGET)));
    run("toitem", "1");
    messages.verify(() -> MessageLoader.send(player, "errors.no-character"), times(3));
    assertTrue(pulses.isEmpty());
    identity.play(player);
    Money.close();
    run("withdraw", "1");
    messages.verify(() -> MessageLoader.send(player, "errors.ledger-unavailable"));
    assertTrue(pulses.isEmpty());
  }

  @Test
  void rankingListsTheRichestCharacters() {
    CharacterRef other = identity.character(UUID.randomUUID());
    Money.fund(Denars.pouch(character), 1250);
    Money.fund(Denars.bank(other), 3000);
    assertTrue(run("baltop"));
    messages.verify(() -> MessageLoader.send(player, "baltop.header", "limit", 20));
    String otherName = "Char-" + other.characterId().toString().substring(0, 4);
    String ownName = "Char-" + character.characterId().toString().substring(0, 4);
    messages.verify(
        () ->
            MessageLoader.send(
                player,
                "baltop.entry",
                "rank",
                1,
                "name",
                otherName,
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
                ownName,
                "amount",
                String.format("%.2f", 12.5)));
    messages.verify(() -> MessageLoader.send(player, "baltop.footer"));
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
    verify(money, never()).pay(any(), anyLong());
    assertEquals(0, Denars.balance(Denars.MINT));
  }

  @Test
  void payDelegatesValidatedCents() {
    assertTrue(run("pay", "12.5"));
    verify(money).pay(player, 1250);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.005", "0.015", "0.010000000000000001", "NaN", "Infinity", "1e1000"})
  void coinCommandsRejectFractionalCentsAndNonFiniteAmounts(String amount) {
    Money.fund(Denars.pouch(character), 500);
    run("pay", amount);
    run("toitem", amount);
    assertEquals(500, pouch());
    verify(money, never()).pay(any(), anyLong());
    messages.verify(() -> MessageLoader.send(player, "errors.invalid-amount"), times(2));
  }

  @Test
  void emptyItemConversionLeavesPouchUntouched() {
    Money.fund(Denars.pouch(character), 500);
    when(money.amountToItems(3, 0, .01)).thenReturn(List.of());
    run("toitem", "3");
    assertEquals(500, pouch());
    messages.verify(() -> MessageLoader.send(player, "errors.coins-unavailable"));
  }

  @Test
  void emptyNamedCoinConversionLeavesPouchUntouched() {
    Coin coin = coin("gold", 2.5, true);
    coins.when(() -> CoinLoader.getByString("gold")).thenReturn(coin);
    when(money.coinItems(coin, 2)).thenReturn(List.of());
    Money.fund(Denars.pouch(character), 500);
    run("toitem", "2", "gold");
    assertEquals(500, pouch());
    messages.verify(() -> MessageLoader.send(player, "errors.coins-unavailable"));
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
    verify(money, never()).coinItems(any(), anyLong());
  }

  @Test
  void toItemChargesThePouchThenGivesOrDropsTheCoins() {
    ItemStack first = new ItemStack(Material.GOLD_NUGGET, 64);
    ItemStack second = new ItemStack(Material.IRON_NUGGET, 1);
    when(money.amountToItems(5, 0, .01)).thenReturn(List.of(first, second));
    run("toitem", "5");
    messages.verify(() -> MessageLoader.send(player, "errors.not-enough-pouch"));
    assertFalse(player.getInventory().contains(Material.GOLD_NUGGET));
    Money.fund(Denars.pouch(character), 1000);
    for (int slot = 0; slot < 36; slot++) {
      if (slot != 0) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
    }
    run("toitem", "5");
    assertEquals(500, pouch());
    assertEquals(-500, Denars.balance(Denars.MINT));
    assertEquals(64, player.getInventory().getItem(0).getAmount());
    assertEquals(1, player.getWorld().getEntities().size(), "The coin that did not fit is dropped");
  }

  @ParameterizedTest
  @ValueSource(strings = {"gold", "2.5"})
  void toItemResolvesCoinByIdOrValueAndChargesCount(String token) {
    Coin coin = coin("gold", 2.5, true);
    coins.when(() -> CoinLoader.getByString("gold")).thenReturn(coin);
    coins.when(() -> CoinLoader.getWithdrawableByValue(2.5)).thenReturn(coin);
    when(money.coinItems(coin, 2)).thenReturn(List.of(new ItemStack(Material.GOLD_NUGGET, 2)));
    Money.fund(Denars.pouch(character), 600);
    run("toitem", "2", token);
    assertEquals(100, pouch());
    assertTrue(player.getInventory().contains(Material.GOLD_NUGGET, 2));
  }

  @ParameterizedTest
  @ValueSource(strings = {"deposit", "withdraw"})
  void cancelledBankPulseDoesNotMoveMoneyOrValidateAmounts(String command) {
    server
        .getPluginManager()
        .registerEvent(
            PlayerBankPulseEvent.class,
            new Listener() {},
            EventPriority.HIGH,
            (listener, event) -> ((PlayerBankPulseEvent) event).setCancelled(true),
            MockBukkit.createMockPlugin());
    run(command);
    assertEquals(1, pulses.size());
    assertSame(player, pulses.getFirst().getPlayer());
    assertEquals(character, pulses.getFirst().getCharacter());
    messages.verifyNoInteractions();
  }

  @ParameterizedTest
  @ValueSource(strings = {"deposit", "withdraw"})
  void bankTransfersMoveBetweenTheCharactersAccountsAndReport(String command) {
    boolean deposit = command.equals("deposit");
    run(command, "4.25");
    String error = deposit ? "errors.not-enough-pouch" : "errors.not-enough-bank";
    messages.verify(() -> MessageLoader.send(player, error));
    Money.fund(Denars.pouch(character), 1000);
    Money.fund(Denars.bank(character), 1000);
    String actionKey = deposit ? "bank.deposited" : "bank.withdrew";
    messages.when(() -> MessageLoader.get(actionKey)).thenReturn("Action");
    run(command, "4.25");
    assertEquals(deposit ? 575 : 1425, pouch());
    assertEquals(deposit ? 1425 : 575, bank());
    messages.verify(() -> MessageLoader.send(player, "bank.header"));
    messages.verify(
        () ->
            MessageLoader.send(
                player, "bank.action", "action", "Action", "amount", new BigDecimal("4.25")));
    messages.verify(
        () ->
            MessageLoader.send(
                player, "bank.new-bank", "amount", BigDecimal.valueOf(bank(), 2)));
    messages.verify(
        () ->
            MessageLoader.send(
                player, "bank.new-pouch", "amount", BigDecimal.valueOf(pouch(), 2)));
    messages.verify(() -> MessageLoader.send(player, "bank.footer"));
  }

  @Test
  void completionsFilterCaseInsensitivelyAndRespectPermissions() {
    assertEquals(
        List.of("bal", "pay", "toitem", "topouch", "deposit", "withdraw", "baltop", "history"),
        tab(""));
    assertEquals(List.of("bal", "baltop"), tab("BA"));
    assertEquals(List.of(), tab("re"));
    assertEquals(List.of(), tab("legacy", ""));
    player.addAttachment(MockBukkit.createMockPlugin(), "denareconomy.reload", true);
    assertEquals(List.of("reload"), tab("re"));
    player.addAttachment(MockBukkit.createMockPlugin(), "denareconomy.admin", true);
    assertEquals(List.of("legacy"), tab("le"));
    assertEquals(List.of("account"), tab("acc"));
    assertEquals(List.of("move"), tab("mo"));
    assertEquals(List.of("assign"), tab("legacy", "a"));
    assertTrue(
        commands
            .onTabComplete(server.getConsoleSender(), deco, "deco", new String[] {""})
            .containsAll(List.of("reload", "give", "legacy", "account", "move")));
    for (String name : List.of("pay", "toitem", "deposit", "withdraw")) {
      assertEquals(List.of(), tab(name, ""));
    }
    assertEquals(List.of(), tab("bal", ""));
    assertEquals(List.of(), tab("pay", "", ""));
    assertEquals(List.of(), tab("toitem", "", "", ""));
    assertEquals(List.of(), tab());
    Coin gold = coin("gold", 2, true);
    Coin blocked = coin("blocked", 1, false);
    coins.when(CoinLoader::get).thenReturn(List.of(gold, blocked));
    assertEquals(List.of("gold"), tab("toitem", "1", "G"));
    assertEquals(List.of(), tab("topouch", ""));
    assertEquals(List.of(), commands.onTabComplete(player, command("pouch"), "pouch", new String[] {""}));
    assertEquals(
        List.of(), commands.onTabComplete(player, command("other"), "other", new String[] {""}));
  }

  @Test
  void targetSuggestionsRequireAnOnlineActiveCharacterAndPreserveStaffPermissions() {
    PlayerMock amy = server.addPlayer("Amy");
    identity.play(amy);
    PlayerMock inactive = server.addPlayer("AbsentCharacter");
    PlayerMock offline = server.addPlayer("AliceOffline");
    identity.play(offline);
    offline.disconnect();
    assertTrue(tab("give", "").isEmpty());
    assertTrue(tab("history", "").isEmpty());
    player.setOp(true);
    assertEquals(List.of("Alex", "Amy"), tab("GIVE", "a"));
    assertEquals(List.of("Alex"), tab("give", "Al"));
    assertEquals(List.of("Alex", "Amy"), tab("HISTORY", "A"));
    assertTrue(tab("history", "Absent").isEmpty());
    assertTrue(tab("history", "Alex", "").isEmpty());
    assertTrue(tab("give", "unknown").isEmpty());
    assertTrue(tab("give", "Alex", "").isEmpty());
    assertTrue(tab("give", "Alex", "12").isEmpty());
    assertEquals(List.of("Alex", "Amy"), commands.onTabComplete(server.getConsoleSender(), deco, "deco", new String[]{"give", ""}));
    for (String verb : List.of("pay", "toitem", "deposit", "withdraw")) {
      assertTrue(tab(verb, "").isEmpty());
      assertTrue(tab(verb, "12").isEmpty());
    }
    Coin gold = coin("gold", 2, true);
    coins.when(CoinLoader::get).thenReturn(List.of(gold));
    assertEquals(List.of("gold"), tab("toitem", "1", "g"));
    identity.stop(amy);
    assertEquals(List.of("Alex"), tab("give", "a"));
  }

  @Test
  void pouchSuppressesTheBukkitPlayerFallbackForVisibleOnlinePlayers() throws Exception {
    server.addPlayer("VisibleOnline");
    var constructor = org.bukkit.command.PluginCommand.class.getDeclaredConstructor(String.class, org.bukkit.plugin.Plugin.class);
    constructor.setAccessible(true);
    var pouch = constructor.newInstance("pouch", MockBukkit.createMockPlugin());
    pouch.setExecutor(commands);
    assertEquals(List.of(), pouch.tabComplete(player, "pouch", new String[]{""}));
    assertEquals(List.of(), pouch.tabComplete(player, "pouch", new String[]{"V"}));
  }

  @Test
  void staffCompletionsUseAuthoritativeSnapshotsAndOnlyTheRemainingNameSuffix() {
    identity.name(character, "Marco Morales");
    CharacterRef other = identity.character(player.getUniqueId());
    identity.name(other, "Marco Marquez");
    CharacterRef archived = identity.character(player.getUniqueId());
    identity.name(archived, "Archived Owner");
    identity.kill(archived);
    CharacterRef dead = identity.character(UUID.randomUUID());
    identity.name(dead, "Dead Character");
    identity.kill(dead);
    Money.fund(AccountIds.bank(dead.characterId()), 50);
    UUID guild = UUID.randomUUID();
    Money.fund(AccountIds.guild(guild), 10);
    Money.fund(AccountIds.guildTax(guild), 20);
    var ledger = spy(DenarEconomy.getLedger());
    DenarEconomy.setLedger(ledger);
    commands.startCompletionRefresh(MockBukkit.createMockPlugin());
    clearInvocations(ledger);
    for (String verb : List.of("history", "account", "move", "legacy")) assertTrue(tab(verb, "").isEmpty());
    player.setOp(true);
    assertEquals(List.of("Marco Marquez", "Marco Morales"), tab("account", "mAr"));
    assertEquals(List.of("Marquez", "Morales"), tab("ACCOUNT", "mArCo", ""));
    assertEquals(List.of("Morales"), tab("account", "Marco", "mO"));
    assertEquals(List.of("Morales"), tab("account", "Marco", "Morales"));
    assertTrue(tab("account", "Marco", "Morales", "").isEmpty());
    assertTrue(tab("account", "not-a-character").isEmpty());
    assertEquals(List.of(character.characterId().toString()), tab("account", character.characterId().toString()));
    assertEquals(List.of("Dead Character"), tab("account", "dead"));
    assertTrue(tab("account", "Archived").isEmpty());
    assertEquals(List.of(archived.characterId().toString()), tab("account", archived.characterId().toString()));
    assertEquals(List.of("server:mint", "server:tax-void"), tab("move", "sErVer:"));
    assertEquals(List.of(AccountIds.bank(dead.characterId())), tab("move", AccountIds.MINT, "char:"));
    assertTrue(tab("move", "missing").isEmpty());
    assertEquals(List.of(AccountIds.guild(guild)), tab("move", AccountIds.MINT, "guild:"));
    assertEquals(List.of(AccountIds.guildTax(guild)), tab("move", AccountIds.MINT, "guildtax:"));
    assertTrue(tab("move", AccountIds.MINT, AccountIds.bank(dead.characterId()), "").isEmpty());
    assertTrue(tab("move", AccountIds.MINT, AccountIds.bank(dead.characterId()), "1", "").isEmpty());
    verify(ledger, never()).accounts(anyString());
    verify(ledger, never()).history(anyList(), anyInt());
    verify(ledger, never()).balance(anyString());
    identity.name(character, "Renamed Character");
    server.getScheduler().performTicks(100);
    assertEquals(List.of("Renamed Character"), tab("account", "renamed"));
    assertTrue(tab("account", "Marco", "Mor").isEmpty());
    assertTrue(ledger.isOpen());
  }

  @Test
  void legacyIdsAreLimitedToUnclaimedAccountsAndTheirOwnCharactersAndRefreshAfterClaim() {
    player.setOp(true);
    UUID owner = player.getUniqueId();
    UUID stranger = UUID.randomUUID();
    CharacterRef sameOwner = identity.character(owner);
    CharacterRef foreign = identity.character(stranger);
    Money.fund(AccountIds.legacyPouch(owner), 20);
    Money.fund(AccountIds.legacyBank(stranger), 30);
    commands.startCompletionRefresh(MockBukkit.createMockPlugin());
    assertEquals(List.of("assign"), tab("legacy", "As"));
    assertEquals(java.util.Set.of(owner.toString(), stranger.toString()), new java.util.HashSet<>(tab("legacy", "assign", "")));
    assertEquals(List.of(owner.toString()), tab("LEGACY", "ASSIGN", owner.toString().substring(0, 15)));
    assertEquals(java.util.Set.of(character.characterId().toString(), sameOwner.characterId().toString()),
        new java.util.HashSet<>(tab("legacy", "assign", owner.toString().toUpperCase(java.util.Locale.ROOT), "")));
    assertEquals(List.of(sameOwner.characterId().toString()), tab("legacy", "assign", owner.toString(), sameOwner.characterId().toString().substring(0, 15)));
    assertEquals(List.of(foreign.characterId().toString()), tab("legacy", "assign", stranger.toString(), ""));
    assertTrue(tab("legacy", "wrong", "").isEmpty());
    assertTrue(tab("legacy", "assign", "missing", "").isEmpty());
    assertTrue(tab("legacy", "assign", owner.toString(), "missing").isEmpty());
    assertTrue(tab("legacy", "assign", owner.toString(), character.characterId().toString(), "").isEmpty());
    run("legacy", "assign", owner.toString(), character.characterId().toString());
    server.getScheduler().performTicks(100);
    assertEquals(List.of(stranger.toString()), tab("legacy", "assign", ""));
    assertTrue(tab("legacy", "assign", owner.toString(), "").isEmpty());
  }

  @Test
  void topouchSendsHeldCoinsToThePouch() {
    assertTrue(run("topouch"));
    assertTrue(run("ToPouch"));
    verify(money, times(2)).toPouch(player);
  }

  @Test
  void giveRejectsPlayersWithoutOperatorStatusOrPermission() {
    assertTrue(run("give", "Alex", "5"));
    messages.verify(() -> MessageLoader.send(player, "errors.no-permission"));
    assertEquals(0, bank());
  }

  @Test
  void giveValidatesUsageAmountAccountAndPlayer() {
    CommandSender console = server.getConsoleSender();
    give(console);
    give(console, "Alex");
    give(console, "Alex", "5", "bank", "extra");
    give(console, "Alex", "5", "wallet");
    messages.verify(() -> MessageLoader.send(console, "errors.give-usage"), times(4));
    give(console, "Alex", "0.005");
    give(console, "Alex", "-1");
    messages.verify(() -> MessageLoader.send(console, "errors.invalid-amount"), times(2));
    give(console, "Nobody", "5");
    messages.verify(() -> MessageLoader.send(console, "errors.unknown-player", "player", "Nobody"));
    server.addPlayer("Idle");
    give(console, "Idle", "5");
    messages.verify(() -> MessageLoader.send(console, "errors.unknown-player", "player", "Idle"));
    assertEquals(0, Denars.balance(Denars.MINT));
  }

  @Test
  void giveCreditsTheCurrentCharactersBankByDefaultAndThePouchOnRequest() {
    CommandSender console = server.getConsoleSender();
    give(console, "Alex", "12.5");
    give(console, "Alex", "0.02", "POUCH");
    give(console, "Alex", "3", "Bank");
    assertEquals(1550, bank());
    assertEquals(2, pouch());
    messages.verify(
        () ->
            MessageLoader.send(
                console,
                "give.sent",
                "amount",
                new BigDecimal("12.50"),
                "player",
                "Alex",
                "account",
                "bank"));
    messages.verify(
        () ->
            MessageLoader.send(
                player, "give.received", "amount", new BigDecimal("0.02"), "account", "pouch"));
    verify(logger)
        .info(
            "CONSOLE gave 12.50 to Alex (character " + character.characterId() + ") bank");
    // Even a dead character can be credited.
    identity.kill(character);
    PlayerMock admin = server.addPlayer("Admin");
    admin.addAttachment(MockBukkit.createMockPlugin(), "denareconomy.give", true);
    assertTrue(commands.onCommand(admin, deco, "deco", new String[] {"GIVE", "Alex", "1"}));
    assertEquals(1650, bank());
  }

  @Test
  void giveReportsAClosedLedgerWithoutClaimingSuccess() {
    CommandSender console = server.getConsoleSender();
    Money.close();
    give(console, "Alex", "5");
    messages.verify(() -> MessageLoader.send(console, "errors.give-failed", "player", "Alex"));
    verify(logger, never()).info(anyString());
  }

  @Test
  void giveCompletionsNeedPermissionAndSuggestEachArgument() {
    assertEquals(List.of(), tab("give", ""));
    player.addAttachment(MockBukkit.createMockPlugin(), "denareconomy.give", true);
    assertEquals(List.of("give"), tab("gi"));
    assertEquals(List.of("Alex"), tab("give", ""));
    assertEquals(List.of(), tab("give", "Alex", ""));
    assertEquals(List.of("bank", "pouch"), tab("give", "Alex", "5", ""));
    assertEquals(List.of("pouch"), tab("Give", "Alex", "5", "P"));
    assertEquals(List.of(), tab("give", "Alex", "5", "bank", ""));
  }

  @Test
  void legacyListsUnclaimedMoneyForStaffOnly() {
    UUID known = player.getUniqueId();
    UUID unknown = UUID.randomUUID();
    Money.fund(AccountIds.legacyPouch(known), 150);
    Money.fund(AccountIds.legacyBank(unknown), 20);
    run("legacy");
    messages.verify(() -> MessageLoader.send(player, "errors.no-permission"));
    CommandSender console = server.getConsoleSender();
    assertTrue(commands.onCommand(console, deco, "deco", new String[] {"legacy"}));
    messages.verify(() -> MessageLoader.send(console, "legacy.header", "count", 2));
    messages.verify(
        () ->
            MessageLoader.send(
                console,
                "legacy.entry",
                "account",
                known,
                "name",
                "Alex",
                "pouch",
                new BigDecimal("1.50"),
                "bank",
                new BigDecimal("0.00")));
    messages.verify(
        () ->
            MessageLoader.send(
                eq(console),
                eq("legacy.entry"),
                eq("account"),
                eq(unknown),
                eq("name"),
                anyString(),
                eq("pouch"),
                eq(new BigDecimal("0.00")),
                eq("bank"),
                eq(new BigDecimal("0.20"))));
  }

  @Test
  void legacyAssignValidatesItsArgumentsAndTheCharactersOwner() {
    CommandSender console = server.getConsoleSender();
    UUID account = player.getUniqueId();
    CharacterRef stranger = identity.character(UUID.randomUUID());
    legacy(console, "assign", account.toString());
    legacy(console, "give", account.toString(), character.characterId().toString());
    legacy(console, "assign", "nope", character.characterId().toString());
    legacy(console, "assign", account.toString(), "nope");
    messages.verify(() -> MessageLoader.send(console, "legacy.usage"), times(4));
    UUID missing = UUID.randomUUID();
    legacy(console, "assign", account.toString(), missing.toString());
    legacy(console, "assign", account.toString(), stranger.characterId().toString());
    messages.verify(
        () ->
            MessageLoader.send(
                console, "legacy.not-owner", "character", missing, "account", account));
    messages.verify(
        () ->
            MessageLoader.send(
                console,
                "legacy.not-owner",
                "character",
                stranger.characterId(),
                "account",
                account));
    legacy(console, "assign", account.toString(), character.characterId().toString());
    messages.verify(() -> MessageLoader.send(console, "legacy.nothing", "account", account));
  }

  @Test
  void legacyAssignMovesTheMoneyToTheChosenCharacterOnce() {
    CommandSender console = server.getConsoleSender();
    UUID account = player.getUniqueId();
    CharacterRef chosen = identity.character(account);
    Money.fund(AccountIds.legacyPouch(account), 150);
    Money.fund(AccountIds.legacyBank(account), 300);
    legacy(console, "ASSIGN", account.toString(), chosen.characterId().toString());
    messages.verify(
        () ->
            MessageLoader.send(
                console, "legacy.assigned", "account", account, "character", chosen.characterId()));
    verify(logger)
        .info("CONSOLE assigned legacy money of " + account + " to character " + chosen.characterId());
    assertEquals(150, Denars.balance(Denars.pouch(chosen)));
    assertEquals(300, Denars.balance(Denars.bank(chosen)));
    legacy(console, "assign", account.toString(), character.characterId().toString());
    messages.verify(() -> MessageLoader.send(console, "legacy.nothing", "account", account));
    assertEquals(0, pouch());
  }

  @Test
  void legacyAssignReportsAClaimThatCannotApply() {
    CommandSender console = server.getConsoleSender();
    UUID account = player.getUniqueId();
    Money.fund(AccountIds.legacyPouch(account), 150);
    Denars.post(
        "claim:" + account,
        "claim",
        List.of(new Leg(Denars.MINT, -1), new Leg(Denars.pouch(character), 1)));
    legacy(console, "assign", account.toString(), character.characterId().toString());
    messages.verify(
        () ->
            MessageLoader.send(
                console,
                "legacy.failed",
                "account",
                account,
                "result",
                net.tfminecraft.denareconomy.api.Result.CONFLICT));
    assertEquals(150, Denars.balance(AccountIds.legacyPouch(account)));
  }

  @Test
  void historyShowsTheCurrentCharactersLatestPostings() {
    messages
        .when(() -> MessageLoader.get(eq("history.change"), any(Object[].class)))
        .thenAnswer(invocation -> invocation.getArgument(2) + " " + invocation.getArgument(4));
    Money.fund(Denars.pouch(character), 500);
    Denars.transfer("d", "deposit", Denars.pouch(character), Denars.bank(character), 200);
    assertTrue(run("history"));
    messages.verify(() -> MessageLoader.send(player, "history.header", "player", "Alex", "count", 2));
    messages.verify(
        () ->
            MessageLoader.send(
                eq(player),
                eq("history.entry"),
                eq("time"),
                anyString(),
                eq("reason"),
                eq("deposit"),
                eq("changes"),
                eq("pouch -2.00, bank +2.00")));
    messages.verify(
        () ->
            MessageLoader.send(
                eq(player),
                eq("history.entry"),
                eq("time"),
                anyString(),
                eq("reason"),
                eq("staff:test"),
                eq("changes"),
                eq("pouch +5.00")));
  }

  @Test
  void historyOfAnotherPlayerOrFromConsoleNeedsStaffAndANamedPlayer() {
    PlayerMock sam = server.addPlayer("Sam");
    run("history", "Sam");
    messages.verify(() -> MessageLoader.send(player, "errors.no-permission"));
    CommandSender console = server.getConsoleSender();
    commands.onCommand(console, deco, "deco", new String[] {"history"});
    messages.verify(() -> MessageLoader.send(console, "general.players-only"));
    commands.onCommand(console, deco, "deco", new String[] {"history", "Sam"});
    messages.verify(() -> MessageLoader.send(console, "errors.unknown-player", "player", "Sam"));
    identity.play(sam);
    commands.onCommand(console, deco, "deco", new String[] {"history", "Sam"});
    messages.verify(() -> MessageLoader.send(console, "history.header", "player", "Sam", "count", 0));
  }

  @Test
  void accountNeedsStaffAndExactlyOneCharacter() {
    run("account", "Kira");
    messages.verify(() -> MessageLoader.send(player, "errors.no-permission"));
    CommandSender console = server.getConsoleSender();
    deco(console, "account");
    messages.verify(() -> MessageLoader.send(console, "account.usage"));
    deco(console, "account", "Kira", "Sun");
    messages.verify(() -> MessageLoader.send(console, "account.unknown", "character", "Kira Sun"));
    deco(console, "account", "Kira");
    messages.verify(() -> MessageLoader.send(console, "account.unknown", "character", "Kira"));
    String unknown = UUID.randomUUID().toString();
    deco(console, "account", unknown);
    messages.verify(() -> MessageLoader.send(console, "account.unknown", "character", unknown));
  }

  @Test
  void accountShowsADeadCharactersBalancesAndPostingsByIdOrName() {
    messages
        .when(() -> MessageLoader.get(eq("history.change"), any(Object[].class)))
        .thenAnswer(invocation -> invocation.getArgument(2) + " " + invocation.getArgument(4));
    CharacterRef dead = identity.character(UUID.randomUUID());
    identity.name(dead, "Ilvaran");
    Money.fund(Denars.pouch(dead), 34_025);
    Money.fund(Denars.bank(dead), 200);
    identity.kill(dead);
    Money.fund(Denars.pouch(character), 5);
    Money.fund(Denars.pouch(new CharacterRef(UUID.randomUUID(), UUID.randomUUID())), 5);
    player.addAttachment(MockBukkit.createMockPlugin(), "denareconomy.admin", true);
    for (String token : List.of("ilvaran", dead.characterId().toString())) {
      run("account", token);
    }
    messages.verify(
        () ->
            MessageLoader.send(
                player,
                "account.header",
                "name",
                "Ilvaran",
                "status",
                CharacterStatus.DEAD,
                "id",
                dead.characterId()),
        times(2));
    messages.verify(
        () ->
            MessageLoader.send(
                player,
                "account.balances",
                "pouch",
                new BigDecimal("340.25"),
                "bank",
                new BigDecimal("2.00")),
        times(2));
    messages.verify(
        () ->
            MessageLoader.send(
                eq(player),
                eq("history.entry"),
                eq("time"),
                anyString(),
                eq("reason"),
                eq("staff:test"),
                eq("changes"),
                eq("bank +2.00")),
        times(2));
  }

  @Test
  void accountFindsANameWithSpaces() {
    CharacterRef dead = identity.character(UUID.randomUUID());
    identity.name(dead, "Marco Morales");
    Money.fund(Denars.bank(dead), 100);
    identity.kill(dead);
    CommandSender console = server.getConsoleSender();
    deco(console, "account", "marco", "morales");
    messages.verify(
        () ->
            MessageLoader.send(
                console,
                "account.header",
                "name",
                "Marco Morales",
                "status",
                CharacterStatus.DEAD,
                "id",
                dead.characterId()));
  }

  @Test
  void accountListsEveryCharacterSharingAName() {
    CharacterRef living = identity.character(UUID.randomUUID());
    CharacterRef rich = identity.character(UUID.randomUUID());
    CharacterRef dead = identity.character(UUID.randomUUID());
    for (CharacterRef kira : List.of(living, rich, dead)) {
      identity.name(kira, "Kira");
    }
    Money.fund(Denars.bank(rich), 10);
    Money.fund(Denars.bank(dead), 10);
    identity.kill(dead);
    CommandSender console = server.getConsoleSender();
    deco(console, "account", "KIRA");
    messages.verify(() -> MessageLoader.send(console, "account.several", "count", 3, "character", "KIRA"));
    for (CharacterRef kira : List.of(living, rich, dead)) {
      messages.verify(
          () ->
              MessageLoader.send(
                  eq(console),
                  eq("account.match"),
                  eq("id"),
                  eq(kira.characterId()),
                  eq("name"),
                  eq("Kira"),
                  eq("status"),
                  any()));
    }
  }

  @Test
  void moveNeedsStaffValidAccountsAndAPositiveAmount() {
    String bank = Denars.bank(character);
    run("move", Denars.MINT, bank, "100", "fix");
    messages.verify(() -> MessageLoader.send(player, "errors.no-permission"));
    CommandSender console = server.getConsoleSender();
    deco(console, "move", Denars.MINT, bank, "100");
    messages.verify(() -> MessageLoader.send(console, "move.usage"));
    deco(console, "move", "bank", bank, "100", "fix");
    messages.verify(() -> MessageLoader.send(console, "move.bad-account", "account", "bank"));
    deco(console, "move", Denars.MINT, "player:alex", "100", "fix");
    messages.verify(
        () -> MessageLoader.send(console, "move.bad-account", "account", "player:alex"));
    for (String cents : List.of("abc", "0", "-5", "1.50")) {
      deco(console, "move", Denars.MINT, bank, cents, "fix");
    }
    messages.verify(() -> MessageLoader.send(console, "errors.invalid-amount"), times(4));
    assertEquals(0, bank());
  }

  @Test
  void moveTakesMoneyFromADeadCharacterAndIsLogged() {
    CharacterRef dead = identity.character(UUID.randomUUID());
    Money.fund(Denars.bank(dead), 500);
    identity.kill(dead);
    CommandSender console = server.getConsoleSender();
    deco(console, "move", Denars.bank(dead), Denars.bank(character), "300", "restore", "after", "revive");
    assertEquals(200, Denars.balance(Denars.bank(dead)));
    assertEquals(300, bank());
    messages.verify(
        () ->
            MessageLoader.send(
                console,
                "move.moved",
                "amount",
                new BigDecimal("3.00"),
                "from",
                Denars.bank(dead),
                "to",
                Denars.bank(character)));
    verify(logger)
        .info(
            argThat(
                (String line) ->
                    line.matches(
                        ".* moved 3.00 from "
                            + Denars.bank(dead)
                            + " to "
                            + Denars.bank(character)
                            + " \\(staff:move:[0-9a-f-]{36}\\): restore after revive")));
    Ledger.Posting posting = DenarEconomy.getLedger().history(List.of(Denars.bank(character)), 1).getFirst();
    assertEquals("staff:move restore after revive", posting.reason());
    assertTrue(posting.opId().startsWith("staff:move:"));
    deco(console, "move", Denars.bank(dead), Denars.bank(character), "201", "too", "much");
    messages.verify(() -> MessageLoader.send(console, "move.failed", "result", Result.REFUSED));
    assertEquals(200, Denars.balance(Denars.bank(dead)));
  }

  @Test
  void moveNeverTakesAGuildBelowZero() {
    String guild = Denars.guild(UUID.randomUUID());
    Money.fund(guild, 100);
    CommandSender console = server.getConsoleSender();
    deco(console, "move", guild, Denars.bank(character), "101", "wages");
    messages.verify(
        () ->
            MessageLoader.send(
                console, "move.guild-short", "account", guild, "balance", new BigDecimal("1.00")));
    assertEquals(100, Denars.balance(guild));
    deco(console, "move", guild, Denars.bank(character), "100", "wages");
    assertEquals(0, Denars.balance(guild));
    deco(console, "move", Denars.MINT, guild, "50", "grant");
    assertEquals(50, Denars.balance(guild));
    assertEquals(100, bank());
  }

  private void deco(CommandSender sender, String... args) {
    assertTrue(commands.onCommand(sender, deco, "deco", args));
  }

  private void give(CommandSender sender, String... args) {
    String[] full = new String[args.length + 1];
    full[0] = "give";
    System.arraycopy(args, 0, full, 1, args.length);
    assertTrue(commands.onCommand(sender, deco, "deco", full));
  }

  private void legacy(CommandSender sender, String... args) {
    String[] full = new String[args.length + 1];
    full[0] = "legacy";
    System.arraycopy(args, 0, full, 1, args.length);
    assertTrue(commands.onCommand(sender, deco, "deco", full));
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
