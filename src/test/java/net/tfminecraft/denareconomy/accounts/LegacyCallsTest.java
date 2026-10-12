package net.tfminecraft.denareconomy.accounts;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.TaxLeg;
import net.tfminecraft.denareconomy.api.TaxPolicy;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.database.BalTopEntry;
import net.tfminecraft.denareconomy.database.Database;
import net.tfminecraft.denareconomy.enums.Accounts;
import net.tfminecraft.denareconomy.ledger.AccountIds;
import net.tfminecraft.denareconomy.loaders.MessageLoader;
import net.tfminecraft.denareconomy.managers.MoneyManager;
import net.tfminecraft.denareconomy.managers.PlayerManager;
import net.tfminecraft.denareconomy.testing.FakeIdentity;
import net.tfminecraft.denareconomy.testing.Money;
import net.tfminecraft.tlibs.character.CharacterRef;
import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

/** The pre-ledger API acts on an online player's current character and refuses anyone else. */
class LegacyCallsTest {
  @TempDir Path folder;
  ServerMock server;
  FakeIdentity identity;
  PlayerMock alex;
  CharacterRef character;
  DenarEconomy previous;
  MockedStatic<MessageLoader> messages;
  final List<LogRecord> logs = new ArrayList<>();
  final MoneyManager money = new MoneyManager();
  final PlayerManager players = new PlayerManager();

  @BeforeEach
  void setUp() throws Exception {
    server = MockBukkit.mock();
    Money.open(folder);
    identity = FakeIdentity.register();
    alex = server.addPlayer("Alex");
    character = identity.play(alex);
    previous = DenarEconomy.plugin;
    DenarEconomy.plugin = mock(DenarEconomy.class);
    Logger log = Logger.getAnonymousLogger();
    log.setUseParentHandlers(false);
    log.addHandler(
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            logs.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        });
    when(DenarEconomy.plugin.getLogger()).thenReturn(log);
    Field logged = LegacyCalls.class.getDeclaredField("logged");
    logged.setAccessible(true);
    ((Set<?>) logged.get(null)).clear();
    messages = mockStatic(MessageLoader.class);
  }

  @AfterEach
  void tearDown() {
    messages.close();
    Money.close();
    MockBukkit.unmock();
    DenarEconomy.plugin = previous;
  }

  long pouch() {
    return Denars.balance(Denars.pouch(character));
  }

  long bank() {
    return Denars.balance(Denars.bank(character));
  }

  void taxTenPercent() {
    TaxPolicy policy =
        (earner, gross) -> Optional.of(new TaxLeg(Denars.guildTax(UUID.randomUUID()), gross / 10));
    Bukkit.getServicesManager()
        .register(TaxPolicy.class, policy, MockBukkit.createMockPlugin(), ServicePriority.Normal);
  }

  List<String> warnings() {
    return logs.stream()
        .filter(r -> r.getLevel() == Level.WARNING)
        .map(LogRecord::getMessage)
        .toList();
  }

  @Test
  void changesAndBalancesActOnTheOnlinePlayersCurrentCharacter() {
    assertTrue(OfflineModifier.apply(alex.getUniqueId(), Accounts.POUCH, 2.5));
    assertTrue(OfflineModifier.apply("Alex", Accounts.POUCH, 1));
    assertEquals(350, pouch());
    assertFalse(OfflineModifier.apply(alex.getUniqueId(), Accounts.POUCH, -10), "No overdraft");
    assertFalse(OfflineModifier.apply(alex.getUniqueId(), Accounts.POUCH, 0.004));
    OfflineModifier.change(alex.getUniqueId(), Accounts.BANK, 3);
    money.changeBal(alex.getUniqueId().toString(), -1, Accounts.BANK);
    money.changeBal("not a uuid", 1, Accounts.BANK);
    money.changeBal(null, 1, Accounts.BANK);
    assertEquals(200, bank());
    assertEquals(3.5, OfflineModifier.balance("Alex", Accounts.POUCH));
    assertEquals(2, money.getBalance(Accounts.BANK, alex.getUniqueId()));
    assertEquals(2, Database.getPlayerBalance(alex.getUniqueId(), Accounts.BANK));
    assertTrue(Database.hasPlayerData(alex.getUniqueId()));
    assertEquals(-550, Denars.balance(Denars.MINT));
    assertTrue(warnings().isEmpty());

    // A switch moves every call to the new character.
    CharacterRef second = identity.character(alex.getUniqueId());
    identity.start(alex, second);
    assertEquals(0, OfflineModifier.balance(alex.getUniqueId(), Accounts.POUCH));
  }

  @Test
  void amountsThatAreNotFiniteOrDoNotFitAreRefusedBeforeConversion() {
    for (double amount :
        new double[] {
          Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, 1e300, -1e300
        }) {
      assertFalse(OfflineModifier.apply(alex.getUniqueId(), Accounts.POUCH, amount));
      money.addMoneyToAccount(alex.getUniqueId().toString(), amount, false, false, Accounts.BANK);
      assertEquals(0, money.doTaxes("Alex", amount));
    }
    assertEquals(0, pouch());
    assertEquals(0, bank());
    assertEquals(0, Denars.balance(Denars.MINT));
    assertEquals(java.util.OptionalLong.of(1235), LegacyCalls.cents(12.345));
    assertEquals(java.util.OptionalLong.of(-1235), LegacyCalls.cents(-12.345));
    assertTrue(LegacyCalls.cents(9.3e16).isEmpty());
    messages.verifyNoInteractions();
  }

  @Test
  void snapshotsShowTheCurrentCharacterAndCannotBeChanged() {
    Money.fund(Denars.pouch(character), 125);
    Money.fund(Denars.bank(character), 300);
    PlayerData data = DenarEconomy.getPlayerManager().get(alex);
    assertEquals(alex.getUniqueId(), data.getId());
    assertEquals(1.25, data.getPouch().getBal());
    assertEquals(3, data.getBank().getBal());
    assertThrows(UnsupportedOperationException.class, () -> data.getPouch().change(-1));
    assertThrows(UnsupportedOperationException.class, () -> data.getBank().setBal(0));
    assertEquals(1.25, Database.loadPlayerData(alex.getUniqueId()).getPouch().getBal());
    assertEquals(125, pouch());
  }

  @Test
  void earningsGoThroughTheTaxPolicyAndTellThePlayer() {
    taxTenPercent();
    assertEquals(1, money.doTaxes("Alex", 10));
    money.addMoney(alex, 5, false, true);
    assertEquals(450, pouch());
    messages.verify(() -> MessageLoader.send(alex, "money.earned", "amount", new BigDecimal("4.50")));
    messages.verify(() -> MessageLoader.send(alex, "money.tax", "tax", new BigDecimal("0.50")));
    money.addMoneyToAccount(alex.getUniqueId().toString(), 2, true, false, Accounts.BANK);
    money.addMoneyToAccount(alex.getUniqueId().toString(), 0, false, false, Accounts.BANK);
    money.addMoney(alex, 1, false, false);
    assertEquals(200, bank());
    assertEquals(550, pouch());
    messages.verify(() -> MessageLoader.send(alex, "money.earned", "amount", new BigDecimal("1.00")));
    messages.verifyNoMoreInteractions();
  }

  @Test
  void anyoneWithoutACurrentCharacterIsRefusedAndEachMethodIsLoggedOnce() {
    identity.stop(alex);
    UUID offline = UUID.randomUUID();
    for (int i = 0; i < 2; i++) {
      assertFalse(OfflineModifier.apply(alex.getUniqueId(), Accounts.POUCH, 1));
      OfflineModifier.change(offline, Accounts.BANK, 1);
      assertEquals(0, OfflineModifier.balance(offline, Accounts.BANK));
      assertEquals(0, players.get(offline).getBank().getBal());
      Database.savePlayerData(players.get(alex));
      money.changeBal(offline.toString(), 1, Accounts.POUCH);
      money.addMoneyToAccount(null, 1, false, true, Accounts.POUCH);
      money.addMoney(alex, 1, false, true);
      assertEquals(0, money.doTaxes(null, 10));
      assertEquals(0, money.doTaxes("Nobody", 10));
      assertEquals(0, money.getBalance(Accounts.POUCH, offline));
      assertFalse(Database.hasPlayerData(offline));
      assertEquals(0, Database.getPlayerBalance(offline, Accounts.POUCH));
      assertEquals(0, Database.loadPlayerData(offline).getPouch().getBal());
    }
    assertEquals(0, Denars.balance(Denars.MINT));
    assertNotNull(new Database(), "Old callers may still construct it");
    List<String> warnings = warnings();
    assertEquals(11, warnings.size(), String.join("\n", warnings));
    assertTrue(warnings.stream().anyMatch(w -> w.startsWith("MoneyManager.addMoneyToAccount ")));
    assertTrue(warnings.stream().anyMatch(w -> w.startsWith("PlayerManager.get ")));
    assertNotNull(logs.getFirst().getThrown(), "The log shows the caller");
    messages.verifyNoInteractions();
  }

  @Test
  void serverTotalsAndTheRankingComeFromTheLedger() {
    CharacterRef rich = identity.character(UUID.randomUUID());
    UUID unknown = UUID.randomUUID();
    Money.fund(Denars.pouch(character), 100);
    Money.fund(Denars.bank(character), 200);
    Money.fund(Denars.bank(rich), 1000);
    Money.fund(AccountIds.bank(unknown), 300);
    Money.fund(AccountIds.legacyPouch(UUID.randomUUID()), 50);
    Money.fund(Denars.guild(UUID.randomUUID()), 999);
    assertEquals(1.5, money.getServerBal(Accounts.POUCH));
    assertEquals(15, Database.getTotalAmount(Accounts.BANK));
    List<BalTopEntry> top = Database.getTopBalances(2);
    assertEquals(2, top.size());
    assertEquals("Char-" + rich.characterId().toString().substring(0, 4), top.get(0).getName());
    assertEquals(10, top.get(0).getTotal());
    String second = top.get(1).getName();
    assertTrue(
        second.equals(unknown.toString().substring(0, 8))
            || second.equals("Char-" + character.characterId().toString().substring(0, 4)));
    assertEquals(3, top.get(1).getTotal());
    assertEquals(3, Database.getTopBalances(20).size());
  }

  @Test
  void joiningRemembersTheName() {
    try (MockedStatic<OfflineModifier> names = mockStatic(OfflineModifier.class)) {
      players.onJoin(new PlayerJoinEvent(alex, net.kyori.adventure.text.Component.empty()));
      names.verify(() -> OfflineModifier.remember("Alex", alex.getUniqueId()));
    }
  }
}
