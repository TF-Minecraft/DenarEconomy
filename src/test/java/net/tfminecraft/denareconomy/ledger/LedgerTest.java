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
import net.tfminecraft.denareconomy.api.TaxLeg;
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
  void writesWithFullSyncAndCheckpointsCommittedPostingsUntilClosed() {
    assertEquals(2, ledger.synchronousLevel(), "WAL with synchronous=FULL");
    String pouch = pouch();
    assertEquals(Result.APPLIED, move("op-sync", "earn", MINT, pouch, 125));
    assertEquals(
        Result.APPLIED,
        ledger.postEarning(
            "coin:relaxed", "earn", List.of(new Leg(MINT, -75), new Leg(pouch, 75)), null, null, true));
    assertEquals(2, ledger.synchronousLevel(), "a relaxed posting puts FULL back");
    assertTrue(ledger.checkpoint());
    assertTrue(ledger.checkpoint());
    assertEquals(200, ledger.balance(pouch));
    assertFalse(severe());
    ledger.close();
    assertFalse(ledger.checkpoint());
    assertEquals(-1, ledger.synchronousLevel());
    ledger = open();
    assertEquals(200, ledger.balance(pouch));
  }

  @Test
  void onlyARelaxedPostingCommitsWithoutItsOwnSync() throws Exception {
    java.lang.reflect.Field field = Ledger.class.getDeclaredField("database");
    field.setAccessible(true);
    var real = (net.tfminecraft.tlibs.database.SqliteDatabase) field.get(ledger);
    Connection spy = org.mockito.Mockito.spy(real.getConnection());
    List<String> pragmas = new ArrayList<>();
    org.mockito.Mockito.doAnswer(
            call -> {
              Statement statement = (Statement) call.callRealMethod();
              // A recording proxy: Mockito spies break the driver's own statement fields.
              return java.lang.reflect.Proxy.newProxyInstance(
                  Statement.class.getClassLoader(),
                  new Class<?>[] {Statement.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("execute")
                        && args != null
                        && args.length == 1
                        && args[0] instanceof String sql
                        && sql.startsWith("PRAGMA synchronous")) pragmas.add(sql);
                    try {
                      return method.invoke(statement, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                      throw e.getCause();
                    }
                  });
            })
        .when(spy)
        .createStatement();
    var database = org.mockito.Mockito.mock(net.tfminecraft.tlibs.database.SqliteDatabase.class);
    org.mockito.Mockito.when(database.getConnection()).thenReturn(spy);
    field.set(ledger, database);
    String pouch = pouch();
    assertEquals(Result.APPLIED, move("synced", "earn", MINT, pouch, 10));
    assertTrue(pragmas.isEmpty(), "an ordinary posting keeps the synced default");
    assertEquals(
        Result.APPLIED,
        ledger.postEarning(
            "coin:x", "earn", List.of(new Leg(MINT, -5), new Leg(pouch, 5)), null, null, true));
    assertEquals(List.of("PRAGMA synchronous=NORMAL", "PRAGMA synchronous=FULL"), pragmas);
    assertEquals(15, ledger.balance(pouch));
    field.set(ledger, real);
  }

  @Test
  void aCheckpointFailingOnADiskErrorClosesTheLedger() throws Exception {
    java.lang.reflect.Field field = Ledger.class.getDeclaredField("database");
    field.setAccessible(true);
    var real = (net.tfminecraft.tlibs.database.SqliteDatabase) field.get(ledger);
    var database = org.mockito.Mockito.mock(net.tfminecraft.tlibs.database.SqliteDatabase.class);
    Connection connection = org.mockito.Mockito.mock(Connection.class);
    org.mockito.Mockito.when(database.getConnection()).thenReturn(connection);
    // SQLITE_IOERR_FSYNC (1034): primary code 10, an I/O error.
    org.mockito.Mockito.when(connection.createStatement())
        .thenThrow(new java.sql.SQLException("disk I/O error", null, 1034));
    field.set(ledger, database);
    assertFalse(ledger.checkpoint());
    assertFalse(ledger.isOpen(), "a disk error refuses postings like any other SQLite error");
    assertTrue(severe());
    real.close();
  }

  @Test
  void aCheckpointReportingABusyDatabaseCountsAsARetry() throws Exception {
    java.lang.reflect.Field field = Ledger.class.getDeclaredField("database");
    field.setAccessible(true);
    var real = (net.tfminecraft.tlibs.database.SqliteDatabase) field.get(ledger);
    var database = org.mockito.Mockito.mock(net.tfminecraft.tlibs.database.SqliteDatabase.class);
    Connection connection = org.mockito.Mockito.mock(Connection.class);
    Statement statement = org.mockito.Mockito.mock(Statement.class);
    ResultSet busy = org.mockito.Mockito.mock(ResultSet.class);
    org.mockito.Mockito.when(database.getConnection()).thenReturn(connection);
    org.mockito.Mockito.when(connection.createStatement()).thenReturn(statement);
    org.mockito.Mockito.when(statement.executeQuery("PRAGMA wal_checkpoint(PASSIVE)")).thenReturn(busy);
    org.mockito.Mockito.when(busy.next()).thenReturn(true, true, false);
    org.mockito.Mockito.when(busy.getInt(1)).thenReturn(1, 0);
    field.set(ledger, database);
    assertFalse(ledger.checkpoint());
    assertTrue(ledger.isOpen());
    assertEquals(1, logs.stream().filter(r -> r.getLevel() == Level.WARNING).count());
    assertTrue(ledger.checkpoint());
    assertTrue(ledger.checkpoint(), "no result row is no busy report");
    assertFalse(severe());
    field.set(ledger, real);
  }

  @Test
  void aRelaxedPostingThatFailsReportsItsOwnErrorAndClosesTheLedger() throws Exception {
    java.lang.reflect.Field field = Ledger.class.getDeclaredField("database");
    field.setAccessible(true);
    var real = (net.tfminecraft.tlibs.database.SqliteDatabase) field.get(ledger);
    Connection spy = org.mockito.Mockito.spy(real.getConnection());
    var database = org.mockito.Mockito.mock(net.tfminecraft.tlibs.database.SqliteDatabase.class);
    org.mockito.Mockito.when(database.getConnection()).thenReturn(spy);
    field.set(ledger, database);
    String pouch = pouch();
    // SQLITE_FULL (13) at commit: the failed transaction is rolled back so the level goes back to FULL.
    org.mockito.Mockito.doThrow(new java.sql.SQLException("database or disk is full", null, 13))
        .when(spy)
        .commit();
    assertEquals(
        Result.UNAVAILABLE,
        ledger.postEarning(
            "coin:full", "earn", List.of(new Leg(MINT, -5), new Leg(pouch, 5)), null, null, true));
    assertFalse(ledger.isOpen());
    LogRecord closed =
        logs.stream().filter(r -> r.getLevel() == Level.SEVERE).findFirst().orElseThrow();
    assertEquals("database or disk is full", closed.getThrown().getMessage());
    assertEquals(0, closed.getThrown().getSuppressed().length);
    ledger = open();
    assertEquals(2, ledger.synchronousLevel());
    assertEquals(0, ledger.balance(pouch), "the failed posting left nothing behind");
    real.close();
  }

  @Test
  void aRelaxedPostingKeepsItsOwnErrorWhenTheRollbackFailsToo() throws Exception {
    java.lang.reflect.Field field = Ledger.class.getDeclaredField("database");
    field.setAccessible(true);
    var real = (net.tfminecraft.tlibs.database.SqliteDatabase) field.get(ledger);
    Connection spy = org.mockito.Mockito.spy(real.getConnection());
    var database = org.mockito.Mockito.mock(net.tfminecraft.tlibs.database.SqliteDatabase.class);
    org.mockito.Mockito.when(database.getConnection()).thenReturn(spy);
    field.set(ledger, database);
    org.mockito.Mockito.doThrow(new java.sql.SQLException("disk I/O error", null, 10)).when(spy).commit();
    org.mockito.Mockito.doThrow(new java.sql.SQLException("cannot roll back")).when(spy).rollback();
    String pouch = pouch();
    assertEquals(
        Result.UNAVAILABLE,
        ledger.postEarning(
            "coin:io", "earn", List.of(new Leg(MINT, -5), new Leg(pouch, 5)), null, null, true));
    LogRecord closed =
        logs.stream().filter(r -> r.getLevel() == Level.SEVERE).findFirst().orElseThrow();
    assertEquals("disk I/O error", closed.getThrown().getMessage());
    assertEquals("cannot roll back", closed.getThrown().getSuppressed()[0].getMessage());
    org.mockito.Mockito.doCallRealMethod().when(spy).rollback();
    spy.rollback();
    real.close();
  }

  @Test
  void aRelaxedPostingThatFailsBeforeItsTransactionStartsStillGoesBackToFullSync() throws Exception {
    java.lang.reflect.Field field = Ledger.class.getDeclaredField("database");
    field.setAccessible(true);
    var real = (net.tfminecraft.tlibs.database.SqliteDatabase) field.get(ledger);
    Connection spy = org.mockito.Mockito.spy(real.getConnection());
    var database = org.mockito.Mockito.mock(net.tfminecraft.tlibs.database.SqliteDatabase.class);
    org.mockito.Mockito.when(database.getConnection()).thenReturn(spy);
    field.set(ledger, database);
    org.mockito.Mockito.doThrow(new java.sql.SQLException("cannot begin"))
        .when(spy)
        .setAutoCommit(false);
    String pouch = pouch();
    assertEquals(
        Result.UNAVAILABLE,
        ledger.postEarning(
            "coin:begin", "earn", List.of(new Leg(MINT, -5), new Leg(pouch, 5)), null, null, true));
    org.mockito.Mockito.verify(spy, org.mockito.Mockito.never()).rollback();
    try (Statement statement = real.getConnection().createStatement();
        ResultSet level = statement.executeQuery("PRAGMA synchronous")) {
      level.next();
      assertEquals(2, level.getInt(1), "the level went back to FULL");
    }
    real.close();
  }

  @Test
  void aBusyCheckpointIsLoggedOnceKeepsTheLedgerOpenAndRecovers() throws Exception {
    java.lang.reflect.Field field = Ledger.class.getDeclaredField("database");
    field.setAccessible(true);
    ((net.tfminecraft.tlibs.database.SqliteDatabase) field.get(ledger)).close();
    var database = org.mockito.Mockito.mock(net.tfminecraft.tlibs.database.SqliteDatabase.class);
    Connection connection = org.mockito.Mockito.mock(Connection.class);
    Statement statement = org.mockito.Mockito.mock(Statement.class);
    org.mockito.Mockito.when(database.getConnection()).thenReturn(connection);
    org.mockito.Mockito.when(connection.createStatement())
        .thenThrow(new java.sql.SQLException("database is locked", null, Ledger.SQLITE_BUSY))
        // SQLITE_LOCKED_SHAREDCACHE (262): primary code 6.
        .thenThrow(new java.sql.SQLException("database table is locked", null, 262))
        .thenReturn(statement);
    ResultSet done = org.mockito.Mockito.mock(ResultSet.class);
    org.mockito.Mockito.when(done.next()).thenReturn(true);
    org.mockito.Mockito.when(statement.executeQuery("PRAGMA wal_checkpoint(PASSIVE)")).thenReturn(done);
    field.set(ledger, database);
    assertFalse(ledger.checkpoint());
    assertFalse(ledger.checkpoint());
    assertTrue(ledger.isOpen(), "a busy checkpoint does not close money");
    assertEquals(1, logs.stream().filter(r -> r.getLevel() == Level.WARNING).count());
    assertTrue(ledger.checkpoint());
    assertTrue(ledger.checkpoint());
    org.mockito.Mockito.verify(statement, org.mockito.Mockito.times(2))
        .executeQuery("PRAGMA wal_checkpoint(PASSIVE)");
    assertEquals(
        1, logs.stream().filter(r -> r.getMessage().equals("Ledger checkpoints work again")).count());
    assertFalse(severe());
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
  void addingTaxAttributionToAnOlderDatabasePreservesItsMoneyAndHistory() throws Exception {
    UUID incarnation = UUID.randomUUID();
    String holding = AccountIds.guildTax(incarnation);
    assertEquals(Result.APPLIED, move("legacy-tax", "earn", MINT, holding, 1234));
    ledger.close();
    try (Connection connection = direct(); Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE tax_earnings");
    }
    ledger = open();
    assertEquals(1234, ledger.balance(holding));
    assertEquals(Optional.of(Result.APPLIED), ledger.status("legacy-tax"));
    assertEquals("legacy-tax", ledger.history(List.of(holding), 10).getFirst().opId());
    assertTrue(ledger.taxContributors(incarnation).isEmpty());
  }

  @Test
  void failingToSaveTaxAttributionRollsBackTheWholePayment() throws Exception {
    UUID incarnation = UUID.randomUUID();
    UUID earner = UUID.randomUUID();
    String holding = AccountIds.guildTax(incarnation);
    String pouch = AccountIds.pouch(earner);
    try (Connection connection = direct(); Statement statement = connection.createStatement()) {
      statement.execute("CREATE TRIGGER fail_tax BEFORE INSERT ON tax_earnings BEGIN SELECT RAISE(ABORT, 'test'); END");
    }
    assertEquals(Result.UNAVAILABLE, ledger.postEarning("tax", "earn",
        List.of(new Leg(MINT, -100), new Leg(pouch, 90), new Leg(holding, 10)),
        earner, new TaxLeg(holding, 10)));
    assertFalse(ledger.isOpen());
    ledger = open();
    assertEquals(0, ledger.balance(MINT));
    assertEquals(0, ledger.balance(pouch));
    assertEquals(0, ledger.balance(holding));
    assertEquals(Optional.empty(), ledger.status("tax"));
    assertTrue(ledger.taxContributors(incarnation).isEmpty());
  }

  @Test
  void taxAttributionMustMatchTheCharacterAndNetHoldingCredit() {
    UUID incarnation = UUID.randomUUID();
    UUID earner = UUID.randomUUID();
    String holding = AccountIds.guildTax(incarnation);
    List<Leg> legs = List.of(new Leg(MINT, -100), new Leg(AccountIds.pouch(earner), 90), new Leg(holding, 10));
    assertThrows(IllegalArgumentException.class,
        () -> ledger.postEarning("tax", "earn", legs, earner, new TaxLeg(holding, 11)));
    assertThrows(IllegalArgumentException.class,
        () -> ledger.postEarning("tax", "earn", legs, earner, new TaxLeg(AccountIds.guildTax(UUID.randomUUID()), 10)));
    assertThrows(IllegalArgumentException.class,
        () -> ledger.postEarning("tax", "earn", legs, null, new TaxLeg(holding, 10)));
    assertThrows(IllegalArgumentException.class,
        () -> ledger.postEarning("tax", "earn", List.of(new Leg(MINT, Long.MIN_VALUE),
            new Leg(holding, Long.MAX_VALUE), new Leg(holding, 1)), earner, new TaxLeg(holding, 10)));
    assertEquals(Optional.empty(), ledger.status("tax"));
    assertEquals(0, ledger.balance(holding));
    assertTrue(ledger.taxContributors(incarnation).isEmpty());
  }

  @Test
  void attributedRetriesCannotChangeTheEarnerOrTaxAccount() {
    UUID first = UUID.randomUUID(), second = UUID.randomUUID(), earner = UUID.randomUUID();
    String holding = AccountIds.guildTax(first), other = AccountIds.guildTax(second);
    List<Leg> legs = List.of(new Leg(MINT, -200), new Leg(holding, 100), new Leg(other, 100));
    TaxLeg tax = new TaxLeg(holding, 100);
    assertEquals(Result.APPLIED, ledger.postEarning("tax", "earn", legs, earner, tax));
    assertEquals(Result.CONFLICT, ledger.postEarning("tax", "earn", legs, UUID.randomUUID(), tax));
    assertEquals(Result.CONFLICT, ledger.postEarning("tax", "earn", legs, earner, null));
    assertEquals(Result.CONFLICT, ledger.post("tax", "earn", legs));
    assertEquals(Result.CONFLICT, ledger.postEarning("tax", "earn", legs, earner, new TaxLeg(other, 100)));
    assertEquals(Result.APPLIED, ledger.postEarning("tax", "earn", legs, earner, tax));
    assertEquals(Map.of(earner, 100L), ledger.taxContributors(first));
    assertTrue(ledger.taxContributors(second).isEmpty());
    // The same legs from an older provider still retain their saved result without invented names.
    assertEquals(Result.APPLIED, ledger.post("legacy", "earn", legs));
    assertEquals(Result.APPLIED, ledger.postEarning("legacy", "earn", legs, earner, tax));
    assertEquals(Map.of(earner, 100L), ledger.taxContributors(first));
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
  void frozenAccountsRefuseDebitsOutsideStaffButTakeCredits() {
    String dead = pouch();
    move("fund", "earn", MINT, dead, 500);
    frozen.add(dead);
    assertEquals(Result.APPLIED, move("wage", "wage", MINT, dead, 100));
    assertEquals(Result.REFUSED, move("spend", "pay", dead, MINT, 100));
    assertEquals(Result.APPLIED, move("fix", "staff:refund", dead, MINT, 100));
    assertEquals(500, ledger.balance(dead));
    // Rules for the kind of account still apply to exempt postings.
    assertEquals(Result.REFUSED, move("over", "staff:fix", dead, MINT, 501));
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
