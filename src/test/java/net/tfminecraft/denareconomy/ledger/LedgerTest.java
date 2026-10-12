package net.tfminecraft.denareconomy.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.tfminecraft.denareconomy.api.Leg;
import net.tfminecraft.denareconomy.api.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

class LedgerTest {
  static final String MINT = AccountIds.MINT;

  @TempDir Path folder;
  final List<LogRecord> logs = new ArrayList<>();
  final Logger log = Logger.getAnonymousLogger();
  final List<String> frozen = new ArrayList<>();
  Ledger ledger;

  @BeforeEach
  void setUp() {
    MockBukkit.mock();
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
    ledger = open();
  }

  @AfterEach
  void tearDown() {
    ledger.close();
    MockBukkit.unmock();
  }

  Ledger open() {
    Predicate<String> isFrozen = frozen::contains;
    return Ledger.open(file(), log, isFrozen);
  }

  File file() {
    return folder.resolve("ledger.db").toFile();
  }

  static String pouch() {
    return AccountIds.pouch(UUID.randomUUID());
  }

  Result move(String opId, String reason, String from, String to, long cents) {
    return ledger.post(opId, reason, List.of(new Leg(from, -cents), new Leg(to, cents)));
  }

  Connection direct() throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + file().getAbsolutePath());
  }

  boolean severe() {
    return logs.stream().anyMatch(r -> r.getLevel() == Level.SEVERE);
  }

  @Test
  void opensInWriteAheadModeAndAppliesBalancedPostings() throws Exception {
    assertTrue(ledger.isOpen());
    String pouch = pouch();
    assertEquals(Result.APPLIED, move("op-1", "earn", MINT, pouch, 250));
    assertEquals(250, ledger.balance(pouch));
    assertEquals(-250, ledger.balance(MINT));
    assertEquals(Optional.of(Result.APPLIED), ledger.status("op-1"));
    assertEquals(Optional.empty(), ledger.status("never"));
    assertEquals(0, ledger.balance(pouch()));
    try (Connection connection = direct();
        Statement statement = connection.createStatement();
        ResultSet mode = statement.executeQuery("PRAGMA journal_mode")) {
      mode.next();
      assertEquals("wal", mode.getString(1));
    }
  }

  @Test
  void retryingAnOperationReturnsItsFirstResultWithoutApplyingAgain() {
    String pouch = pouch();
    assertEquals(Result.APPLIED, move("coin:1", "earn", MINT, pouch, 100));
    assertEquals(Result.APPLIED, move("coin:1", "earn", MINT, pouch, 100));
    assertEquals(100, ledger.balance(pouch));
    assertEquals(1, ledger.history(List.of(pouch), 20).size());
  }

  @Test
  void reusingAnOperationIdWithDifferentLegsIsAConflict() {
    String pouch = pouch();
    assertEquals(Result.APPLIED, move("op", "earn", MINT, pouch, 100));
    assertEquals(Result.CONFLICT, move("op", "earn", MINT, pouch, 101));
    assertEquals(Result.CONFLICT, move("op", "other", MINT, pouch, 100));
    assertEquals(100, ledger.balance(pouch));
    assertEquals(Optional.of(Result.APPLIED), ledger.status("op"));
    assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.WARNING));
  }

  @Test
  void legOrderDoesNotChangeTheFingerprint() {
    String pouch = pouch();
    assertEquals(Result.APPLIED, move("op", "earn", MINT, pouch, 100));
    assertEquals(
        Result.APPLIED,
        ledger.post("op", "earn", List.of(new Leg(pouch, 100), new Leg(MINT, -100))));
  }

  @Test
  void refusalsAreRecordedSoARetryIsRefusedAgain() {
    String pouch = pouch();
    String bank = AccountIds.bank(UUID.randomUUID());
    assertEquals(Result.REFUSED, move("pay", "deposit", pouch, bank, 100));
    assertEquals(Optional.of(Result.REFUSED), ledger.status("pay"));
    move("fund", "earn", MINT, pouch, 500);
    assertEquals(Result.REFUSED, move("pay", "deposit", pouch, bank, 100));
    assertEquals(500, ledger.balance(pouch));
    assertEquals(0, ledger.balance(bank));
    assertEquals(Result.APPLIED, move("pay-2", "deposit", pouch, bank, 500));
    assertEquals(0, ledger.balance(pouch));
  }

  @Test
  void onlySystemAccountsAndGuildTreasuriesMayGoBelowZero() {
    UUID guild = UUID.randomUUID();
    String treasury = AccountIds.guild(guild);
    String holding = AccountIds.guildTax(guild);
    String legacy = AccountIds.legacyBank(UUID.randomUUID());
    assertEquals(Result.APPLIED, move("a", "upkeep", treasury, AccountIds.TAX_VOID, 300));
    assertEquals(-300, ledger.balance(treasury));
    assertEquals(Result.APPLIED, move("b", "earn", AccountIds.TAX_VOID, pouch(), 400));
    assertEquals(-100, ledger.balance(AccountIds.TAX_VOID));
    assertEquals(Result.REFUSED, move("c", "settle", holding, treasury, 1));
    assertEquals(Result.REFUSED, move("d", "claim", legacy, pouch(), 1));
    assertEquals(Result.REFUSED, move("e", "pay", pouch(), MINT, 1));
  }

  @Test
  void legsOnOneAccountAreAddedTogetherBeforeChecking() {
    String pouch = pouch();
    String other = pouch();
    move("fund", "earn", MINT, pouch, 100);
    // Debiting 150 and crediting 100 back nets to -50, which the pouch covers.
    assertEquals(
        Result.APPLIED,
        ledger.post(
            "net",
            "trade",
            List.of(new Leg(pouch, -150), new Leg(pouch, 100), new Leg(other, 50))));
    assertEquals(50, ledger.balance(pouch));
    assertEquals(Map.of(pouch, -50L), ledger.history(List.of(pouch), 1).getFirst().legs());
    assertEquals(Result.APPLIED, move("self", "noop", pouch, pouch, 10));
    assertEquals(Optional.of(Result.APPLIED), ledger.status("self"));
    assertEquals(50, ledger.balance(pouch));
    assertEquals("net", ledger.history(List.of(pouch), 5).getFirst().opId());
  }

  @Test
  void overflowingSumsAndBalancesAreRefused() {
    String pouch = pouch();
    List<Leg> huge =
        List.of(
            new Leg(MINT, Long.MAX_VALUE),
            new Leg(MINT, Long.MAX_VALUE),
            new Leg(pouch, -Long.MAX_VALUE),
            new Leg(pouch, -Long.MAX_VALUE));
    assertEquals(Result.REFUSED, ledger.post("sum", "earn", huge));
    assertEquals(Optional.of(Result.REFUSED), ledger.status("sum"));
    assertEquals(Result.REFUSED, ledger.post("sum", "earn", huge));
    List<Leg> perAccount =
        List.of(
            new Leg(pouch, Long.MAX_VALUE),
            new Leg(MINT, -Long.MAX_VALUE),
            new Leg(pouch, Long.MAX_VALUE),
            new Leg(MINT, -Long.MAX_VALUE));
    assertEquals(Result.REFUSED, ledger.post("net", "earn", perAccount));
    assertEquals(Result.APPLIED, move("max", "earn", MINT, pouch, Long.MAX_VALUE));
    assertEquals(Result.REFUSED, move("more", "earn", MINT, pouch, 1));
    assertEquals(Long.MAX_VALUE, ledger.balance(pouch));
  }

  @Test
  void malformedPostingsAreRejectedWithoutRecording() {
    String pouch = pouch();
    List<Leg> legs = List.of(new Leg(MINT, -1), new Leg(pouch, 1));
    assertThrows(IllegalArgumentException.class, () -> ledger.post(null, "r", legs));
    assertThrows(IllegalArgumentException.class, () -> ledger.post(" ", "r", legs));
    assertThrows(IllegalArgumentException.class, () -> ledger.post("op", null, legs));
    assertThrows(IllegalArgumentException.class, () -> ledger.post("op", "", legs));
    assertThrows(IllegalArgumentException.class, () -> ledger.post("op", "r", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> ledger.post("op", "r", List.of(new Leg(MINT, -1), new Leg(pouch, 2))));
    assertThrows(
        IllegalArgumentException.class,
        () -> ledger.post("op", "r", List.of(new Leg(MINT, -1), new Leg("player:alex", 1))));
    assertThrows(IllegalArgumentException.class, () -> ledger.balance("guild:Rome"));
    assertEquals(Optional.empty(), ledger.status("op"));
  }

  @Test
  void frozenAccountsRefuseDebitsOutsideStaffAndEstatesButTakeCredits() {
    String dead = pouch();
    move("fund", "earn", MINT, dead, 500);
    frozen.add(dead);
    assertEquals(Result.APPLIED, move("wage", "wage", MINT, dead, 100));
    assertEquals(Result.REFUSED, move("spend", "pay", dead, MINT, 100));
    assertEquals(Result.APPLIED, move("fix", "staff:refund", dead, MINT, 100));
    assertEquals(Result.APPLIED, move("will", "estate:heir", dead, pouch(), 100));
    assertEquals(400, ledger.balance(dead));
    // Rules for the kind of account still apply to exempt postings.
    assertEquals(Result.REFUSED, move("over", "staff:fix", dead, MINT, 401));
    frozen.add(AccountIds.guild(UUID.randomUUID()));
    assertEquals(Result.REFUSED, move("g", "upkeep", frozen.getLast(), MINT, 1));
  }

  @Test
  void anSqliteErrorClosesTheLedgerAndLeavesNoPartialPosting() throws Exception {
    String pouch = pouch();
    move("fund", "earn", MINT, pouch, 100);
    try (Connection connection = direct();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE legs");
    }
    assertEquals(Result.UNAVAILABLE, move("broken", "earn", MINT, pouch, 50));
    assertFalse(ledger.isOpen());
    assertTrue(severe());
    assertEquals(Result.UNAVAILABLE, move("later", "earn", MINT, pouch, 50));
    assertEquals(0, ledger.balance(pouch));
    assertEquals(Optional.empty(), ledger.status("fund"));
    assertEquals(List.of(), ledger.accounts("char"));
    assertEquals(List.of(), ledger.history(List.of(pouch), 5));
    try (Connection connection = direct();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT COUNT(*), (SELECT cents FROM accounts WHERE id = '" + pouch + "')"
                    + " FROM operations WHERE op_id = 'broken'")) {
      rows.next();
      assertEquals(0, rows.getInt(1));
      assertEquals(100, rows.getLong(2));
    }
  }

  @Test
  void aFailedReadAlsoClosesTheLedger() throws Exception {
    try (Connection connection = direct();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE accounts");
    }
    assertEquals(0, ledger.balance(pouch()));
    assertFalse(ledger.isOpen());
    assertTrue(severe());
  }

  @Test
  void aLedgerThatCannotOpenRefusesEverything() throws Exception {
    Path notAFolder = Files.writeString(folder.resolve("file.txt"), "");
    Ledger closed = Ledger.open(notAFolder.resolve("ledger.db").toFile(), log, frozen::contains);
    assertFalse(closed.isOpen());
    assertTrue(severe());
    assertEquals(
        Result.UNAVAILABLE,
        closed.post("op", "earn", List.of(new Leg(MINT, -1), new Leg(pouch(), 1))));
    closed.close();

    logs.clear();
    Path garbage = folder.resolve("garbage.db");
    Files.writeString(garbage, "this is not a database, just some text that is long enough");
    Ledger unreadable = Ledger.open(garbage.toFile(), log, frozen::contains);
    assertFalse(unreadable.isOpen());
    assertTrue(severe());

    Ledger unavailable = Ledger.unavailable();
    assertFalse(unavailable.isOpen());
    assertEquals(0, unavailable.balance(MINT));
  }

  @Test
  void balancesAndOperationsSurviveCloseAndReopen() {
    String pouch = pouch();
    move("op", "earn", MINT, pouch, 75);
    ledger.close();
    ledger.close();
    assertFalse(ledger.isOpen());
    assertEquals(Result.UNAVAILABLE, move("closed", "earn", MINT, pouch, 1));
    ledger = open();
    assertEquals(75, ledger.balance(pouch));
    assertEquals(Result.APPLIED, move("op", "earn", MINT, pouch, 75));
    assertEquals(75, ledger.balance(pouch));
    assertEquals(Optional.empty(), ledger.status("closed"));
  }

  @Test
  void everyCallMustBeOnTheMainThread() throws Exception {
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                ledger.balance(MINT);
              } catch (Throwable e) {
                thrown.set(e);
              }
            });
    worker.start();
    worker.join();
    assertInstanceOf(IllegalStateException.class, thrown.get());
    Thread opener =
        new Thread(
            () -> {
              try {
                open();
              } catch (Throwable e) {
                thrown.set(e);
              }
            });
    thrown.set(null);
    opener.start();
    opener.join();
    assertInstanceOf(IllegalStateException.class, thrown.get());
  }

  @Test
  void historyListsTheLatestOperationsTouchingTheAccountsNewestFirst() {
    UUID character = UUID.randomUUID();
    String pouch = AccountIds.pouch(character);
    String bank = AccountIds.bank(character);
    move("one", "earn", MINT, pouch, 300);
    move("elsewhere", "earn", MINT, pouch(), 5);
    move("two", "deposit", pouch, bank, 200);
    move("three", "withdraw", bank, pouch, 50);
    List<Ledger.Posting> latest = ledger.history(List.of(pouch, bank), 2);
    assertEquals(List.of("three", "two"), latest.stream().map(Ledger.Posting::opId).toList());
    assertEquals(Map.of(bank, -50L, pouch, 50L), latest.getFirst().legs());
    assertEquals("withdraw", latest.getFirst().reason());
    assertTrue(latest.getFirst().created() > 0);
    assertEquals(3, ledger.history(List.of(pouch, bank), 20).size());
    assertEquals(Map.of(pouch, 300L), ledger.history(List.of(pouch), 20).getLast().legs());
    assertEquals(List.of(), ledger.history(List.of(), 20));
    assertTrue(ledger.isOpen(), "SQLite accepts an empty IN list");
  }

  @Test
  void accountsAreListedByKindWithTheirParsedParts() {
    UUID character = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    move("a", "earn", MINT, AccountIds.pouch(character), 10);
    move("b", "import", MINT, AccountIds.legacyBank(account), 20);
    assertEquals(
        List.of(
            new Ledger.Row(
                AccountIds.pouch(character), "char", null, character.toString(), "pouch", 10)),
        ledger.accounts("char"));
    assertEquals(
        List.of(
            new Ledger.Row(
                AccountIds.legacyBank(account), "legacy", account.toString(), null, "bank", 20)),
        ledger.accounts("legacy"));
    assertEquals(
        List.of(new Ledger.Row(MINT, "server", null, null, "mint", -30)), ledger.accounts("server"));
  }
}
