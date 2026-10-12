package net.tfminecraft.denareconomy.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.tfminecraft.denareconomy.api.Leg;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.denareconomy.testing.FakeIdentity;
import net.tfminecraft.tlibs.character.CharacterRef;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class LegacyMigrationTest {
  @TempDir Path data;
  ServerMock server;
  FakeIdentity identity;
  Ledger ledger;
  final List<LogRecord> logs = new ArrayList<>();
  final Logger log = Logger.getAnonymousLogger();
  final UUID alex = UUID.randomUUID();
  final UUID sam = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    identity = FakeIdentity.register();
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
    return Ledger.open(data.resolve("ledger.db").toFile(), log, account -> false);
  }

  LegacyMigration migration() {
    return new LegacyMigration(ledger, data.toFile(), log);
  }

  Path players() {
    return data.resolve("PlayerData");
  }

  void account(UUID id, String pouch, String bank) throws Exception {
    Files.createDirectories(players());
    Files.writeString(
        players().resolve(id + ".json"),
        "{\"id\":\""
            + id
            + "\",\"pouch\":{\"amount\":"
            + pouch
            + ",\"taxable\":true},\"bank\":{\"amount\":"
            + bank
            + ",\"taxable\":false}}");
  }

  boolean logged(Level level) {
    return logs.stream().anyMatch(r -> r.getLevel() == level);
  }

  long total() {
    long sum = 0;
    for (String kind : List.of("char", "legacy")) {
      for (Ledger.Row row : ledger.accounts(kind)) sum += row.cents();
    }
    return sum;
  }

  @Test
  void importsEveryAccountFileThenRenamesTheFolder() throws Exception {
    account(alex, "12.5", "100");
    account(sam, "0", "0.07");
    Files.writeString(players().resolve(alex + "-123.tmp"), "half written");
    assertTrue(migration().importFiles());
    assertFalse(Files.exists(players()));
    assertTrue(Files.isRegularFile(data.resolve("PlayerData.imported").resolve(alex + ".json")));
    assertEquals(1250, ledger.balance(AccountIds.legacyPouch(alex)));
    assertEquals(10000, ledger.balance(AccountIds.legacyBank(alex)));
    assertEquals(7, ledger.balance(AccountIds.legacyBank(sam)));
    assertEquals(-11257, ledger.balance(AccountIds.MINT));
    assertEquals(java.util.Optional.of(Result.APPLIED), ledger.status("import:" + alex));
    assertEquals(
        Map.of(
            alex, new LegacyMigration.Unclaimed(1250, 10000),
            sam, new LegacyMigration.Unclaimed(0, 7)),
        migration().unclaimed());
    assertTrue(migration().importFiles(), "Nothing is left to import");
  }

  @Test
  void aCrashBeforeTheRenameReimportsNothing() throws Exception {
    account(alex, "5", "6");
    Files.createDirectories(data.resolve("PlayerData.imported"));
    Files.writeString(data.resolve("PlayerData.imported").resolve("keep"), "");
    assertFalse(migration().importFiles());
    assertTrue(logged(Level.SEVERE));
    assertEquals(500, ledger.balance(AccountIds.legacyPouch(alex)));

    ledger.close();
    ledger = open();
    Files.delete(data.resolve("PlayerData.imported").resolve("keep"));
    Files.delete(data.resolve("PlayerData.imported"));
    assertTrue(migration().importFiles());
    assertEquals(500, ledger.balance(AccountIds.legacyPouch(alex)));
    assertEquals(600, ledger.balance(AccountIds.legacyBank(alex)));
    assertEquals(1100, total());
  }

  @Test
  void anUnreadableFileKeepsTheFolderForTheNextStart() throws Exception {
    account(alex, "1", "2");
    Files.writeString(players().resolve(sam + ".json"), "");
    Files.writeString(players().resolve("notes.json"), "{}");
    Files.writeString(players().resolve(UUID.randomUUID() + ".json"), "{\"pouch\":{}}");
    assertFalse(migration().importFiles());
    assertTrue(Files.isDirectory(players()));
    assertTrue(logged(Level.SEVERE));
    assertEquals(100, ledger.balance(AccountIds.legacyPouch(alex)));
  }

  @Test
  void negativeOrMissingBalancesImportAsZero() throws Exception {
    account(alex, "-3", "4");
    account(sam, "5", "-6");
    UUID empty = UUID.randomUUID();
    Files.writeString(players().resolve(empty + ".json"), "{\"id\":\"" + empty + "\"}");
    assertTrue(migration().importFiles());
    assertTrue(logged(Level.WARNING));
    assertEquals(0, ledger.balance(AccountIds.legacyPouch(alex)));
    assertEquals(400, ledger.balance(AccountIds.legacyBank(alex)));
    assertEquals(500, ledger.balance(AccountIds.legacyPouch(sam)));
    assertEquals(0, ledger.balance(AccountIds.legacyBank(sam)));
    assertEquals(Map.of(alex, 400L, sam, 500L).keySet(), migration().unclaimed().keySet());
  }

  @Test
  void aFileChangedAfterItsImportOrAClosedLedgerKeepsTheFolder() throws Exception {
    account(alex, "1", "2");
    ledger.post(
        "import:" + alex,
        "import",
        List.of(new Leg(AccountIds.MINT, -1), new Leg(AccountIds.legacyPouch(alex), 1)));
    assertFalse(migration().importFiles());
    assertEquals(1, ledger.balance(AccountIds.legacyPouch(alex)));
    ledger.close();
    account(sam, "1", "2");
    assertFalse(migration().importFiles());
    assertTrue(Files.isDirectory(players()));
  }

  @Test
  void claimsWaitForHeirsAndMoveLegacyMoneyOnce() throws Exception {
    account(alex, "10", "20");
    account(sam, "3", "0");
    LegacyMigration migration = migration();
    migration.importFiles();
    CharacterRef heir = identity.character(alex);
    identity.heirs.put(alex, heir);
    Plugin plugin = MockBukkit.createMockPlugin();
    migration.start(plugin);
    assertFalse(migration.claimWhenReady());
    server.getPluginManager().callEvent(new PluginEnableEvent(plugin));
    assertEquals(1000, ledger.balance(AccountIds.legacyPouch(alex)));

    identity.heirsReady = true;
    server.getScheduler().performTicks(100);
    assertEquals(0, ledger.balance(AccountIds.legacyPouch(alex)));
    assertEquals(1000, ledger.balance(AccountIds.pouch(heir.characterId())));
    assertEquals(2000, ledger.balance(AccountIds.bank(heir.characterId())));
    assertEquals(Map.of(sam, new LegacyMigration.Unclaimed(300, 0)), migration.unclaimed());
    // Polling and listening stop once claims have run: a heir found later waits for a restart.
    identity.heirs.put(sam, identity.character(sam));
    server.getScheduler().performTicks(200);
    assertTrue(migration.claimWhenReady());
    server.getPluginManager().callEvent(new PluginEnableEvent(plugin));
    assertEquals(300, ledger.balance(AccountIds.legacyPouch(sam)));

    // A restart claims again: the claimed account no longer holds legacy money.
    ledger.close();
    ledger = open();
    LegacyMigration restarted = migration();
    restarted.start(plugin);
    assertEquals(1000, ledger.balance(AccountIds.pouch(heir.characterId())));
    assertEquals(0, ledger.balance(AccountIds.legacyPouch(sam)));
    assertEquals(3300, total());
    assertEquals(Result.CONFLICT, restarted.claim(alex, heir), "A second claim cannot move money");
    assertEquals(1000, ledger.balance(AccountIds.pouch(heir.characterId())));
  }

  @Test
  void aClaimInterruptedBeforeCommitIsRetriedOnTheNextStart() throws Exception {
    account(alex, "10", "20");
    migration().importFiles();
    CharacterRef heir = identity.character(alex);
    identity.heirs.put(alex, heir);
    identity.heirsReady = true;
    // The first attempt dies inside its transaction.
    try (var connection =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + data.resolve("ledger.db").toAbsolutePath());
        var statement = connection.createStatement()) {
      statement.execute("CREATE TRIGGER fail BEFORE INSERT ON legs BEGIN SELECT RAISE(ABORT, 'crash'); END");
    }
    migration().start(MockBukkit.createMockPlugin());
    assertFalse(ledger.isOpen());
    ledger = open();
    assertEquals(1000, ledger.balance(AccountIds.legacyPouch(alex)));
    assertEquals(0, ledger.balance(AccountIds.pouch(heir.characterId())));
    try (var connection =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + data.resolve("ledger.db").toAbsolutePath());
        var statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER fail");
    }
    migration().start(MockBukkit.createMockPlugin());
    assertEquals(0, ledger.balance(AccountIds.legacyPouch(alex)));
    assertEquals(1000, ledger.balance(AccountIds.pouch(heir.characterId())));
    assertEquals(3000, total());
  }

  @Test
  void anAccountWhoseClaimIdWasUsedDifferentlyStaysForStaff() throws Exception {
    account(alex, "10", "0");
    migration().importFiles();
    CharacterRef heir = identity.character(alex);
    identity.heirs.put(alex, heir);
    identity.heirsReady = true;
    ledger.post(
        "claim:" + alex,
        "claim",
        List.of(new Leg(AccountIds.MINT, -1), new Leg(AccountIds.pouch(heir.characterId()), 1)));
    migration().start(MockBukkit.createMockPlugin());
    assertEquals(1000, ledger.balance(AccountIds.legacyPouch(alex)));
    assertTrue(migration().unclaimed().containsKey(alex));
  }
}
