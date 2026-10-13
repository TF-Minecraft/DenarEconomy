package net.tfminecraft.denareconomy.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.tfminecraft.denareconomy.api.Leg;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.denareconomy.testing.FakeIdentity;
import net.tfminecraft.denareconomy.testing.Money;
import net.tfminecraft.tlibs.character.CharacterIdentity;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

class EstateSweepTest {
  @TempDir Path folder;
  FakeIdentity identity;
  Ledger ledger;
  CharacterRef dead;
  UUID deathId;
  String pouch;
  String bank;
  final String heirBank = AccountIds.bank(UUID.randomUUID());

  @BeforeEach
  void setUp() {
    MockBukkit.mock();
    ledger = Money.open(folder);
    identity = FakeIdentity.register();
    dead = identity.character(UUID.randomUUID());
    pouch = AccountIds.pouch(dead.characterId());
    bank = AccountIds.bank(dead.characterId());
  }

  @AfterEach
  void tearDown() {
    Money.close();
    MockBukkit.unmock();
  }

  Ledger.Sweep sweep() {
    return ledger.sweepEstate(deathId, dead.characterId(), heirBank);
  }

  long operations() throws Exception {
    try (Connection connection = direct();
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM operations")) {
      row.next();
      return row.getLong(1);
    }
  }

  Connection direct() {
    try {
      return DriverManager.getConnection(
          "jdbc:sqlite:" + folder.resolve("ledger.db").toAbsolutePath());
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Stores an operation row as an older jar or an earlier refusal would have left it. */
  void store(String opId, String status) throws Exception {
    try (Connection connection = direct();
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO operations(op_id, fingerprint, status, created, reason)"
                    + " VALUES (?, 'x', ?, 0, 'old')")) {
      insert.setString(1, opId);
      insert.setString(2, status);
      insert.executeUpdate();
    }
  }

  long total() {
    long sum = 0;
    for (String kind : List.of("char", "legacy", "guild", "guildtax", "server")) {
      for (Ledger.Row row : ledger.accounts(kind)) {
        sum += row.cents();
      }
    }
    return sum;
  }

  @Test
  void sweepsBothAccountsOfTheDeadCharacterAsOneNumberedPosting() {
    Money.fund(pouch, 300);
    Money.fund(bank, 1_000);
    deathId = identity.kill(dead);
    Ledger.Sweep sweep = sweep();
    assertEquals(
        new Ledger.Sweep(Result.APPLIED, Optional.of("estate:" + deathId + ":1"), 1_300), sweep);
    assertEquals(0, ledger.balance(pouch));
    assertEquals(0, ledger.balance(bank));
    assertEquals(1_300, ledger.balance(heirBank));
    Ledger.Posting posting = ledger.history(List.of(heirBank), 1).getFirst();
    assertEquals("estate:" + deathId, posting.reason());
    assertEquals(
        List.of(new Ledger.EstatePosting("estate:" + deathId + ":1", 1, Result.APPLIED, 1_300)),
        ledger.estatePostings(deathId));
    assertEquals(0, total(), "every cent is accounted for");
  }

  @Test
  void zeroBalancesPostNothingAndAZeroLegStillCountsInTheFingerprint() throws Exception {
    deathId = identity.kill(dead);
    assertEquals(new Ledger.Sweep(Result.APPLIED, Optional.empty(), 0), sweep());
    assertEquals(0, operations());
    Money.fund(pouch, 300);
    String opId = "estate:" + deathId + ":1";
    assertEquals(new Ledger.Sweep(Result.APPLIED, Optional.of(opId), 300), sweep());
    List<String> lines = new ArrayList<>(List.of(bank + "=0", pouch + "=-300", heirBank + "=300"));
    java.util.Collections.sort(lines);
    String fingerprint =
        UUID.nameUUIDFromBytes(
                ("estate:" + deathId + "\n" + String.join("\n", lines))
                    .getBytes(StandardCharsets.UTF_8))
            .toString();
    try (Connection connection = direct();
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery(
                "SELECT fingerprint, (SELECT COUNT(*) FROM legs WHERE op_id = '"
                    + opId
                    + "') FROM operations WHERE op_id = '"
                    + opId
                    + "'")) {
      row.next();
      assertEquals(fingerprint, row.getString(1));
      assertEquals(2, row.getInt(2), "the zero bank leg is not stored");
    }
    Money.fund(bank, 40);
    assertEquals(
        new Ledger.Sweep(Result.APPLIED, Optional.of("estate:" + deathId + ":2"), 40), sweep());
    assertEquals(340, ledger.balance(heirBank));
  }

  @Test
  void theNextNumberIsAboveEveryStoredOneWhateverItsStatus() throws Exception {
    deathId = identity.kill(dead);
    store("estate:" + deathId + ":2", "APPLIED");
    store("estate:" + deathId + ":10", "REFUSED");
    store("estate:" + deathId + ":legacy", "APPLIED");
    store("estate:" + UUID.randomUUID() + ":40", "APPLIED");
    Money.fund(bank, 5);
    assertEquals(
        new Ledger.Sweep(Result.APPLIED, Optional.of("estate:" + deathId + ":11"), 5), sweep());
    assertEquals(
        List.of(2L, 10L, 11L),
        ledger.estatePostings(deathId).stream().map(Ledger.EstatePosting::number).toList());
    assertEquals(Result.REFUSED, ledger.estatePostings(deathId).get(1).result());
  }

  @Test
  void aRefusedSweepKeepsItsNumberSoTheNextSweepCannotConflict() {
    UUID guild = UUID.randomUUID();
    String treasury = AccountIds.guild(guild);
    ledger.post(
        "full",
        "staff:test",
        List.of(new Leg(AccountIds.TAX_VOID, -Long.MAX_VALUE), new Leg(treasury, Long.MAX_VALUE)));
    Money.fund(bank, 100);
    deathId = identity.kill(dead);
    String first = "estate:" + deathId + ":1";
    assertEquals(
        new Ledger.Sweep(Result.REFUSED, Optional.of(first), 0),
        ledger.sweepEstate(deathId, dead.characterId(), treasury));
    assertEquals(Optional.of(Result.REFUSED), ledger.status(first));
    ledger.post(
        "drain",
        "staff:test",
        List.of(new Leg(treasury, -1_000), new Leg(AccountIds.TAX_VOID, 1_000)));
    assertEquals(
        new Ledger.Sweep(Result.APPLIED, Optional.of("estate:" + deathId + ":2"), 100),
        ledger.sweepEstate(deathId, dead.characterId(), treasury));
    assertEquals(Optional.of(Result.REFUSED), ledger.status(first));
    assertEquals(0, ledger.balance(bank));
  }

  @Test
  void nothingIsRecordedUnlessTheCharacterIsDeadWithThisDeath() throws Exception {
    Money.fund(bank, 100);
    long before = operations();
    deathId = UUID.randomUUID();
    assertEquals(Result.REFUSED, sweep().result(), "alive");
    identity.kill(dead);
    assertEquals(Result.REFUSED, sweep().result(), "dead from another death");
    deathId = identity.kill(dead);
    identity.set(dead, CharacterStatus.MISSING);
    assertEquals(Result.REFUSED, sweep().result(), "missing stays with staff");
    identity.revive(dead);
    assertEquals(Result.REFUSED, sweep().result(), "revived");
    assertEquals(
        Result.REFUSED,
        ledger.sweepEstate(deathId, UUID.randomUUID(), heirBank).result(),
        "unknown character");
    identity.set(dead, CharacterStatus.DEAD);
    Bukkit.getServicesManager().unregister(CharacterIdentity.class, identity);
    assertEquals(new Ledger.Sweep(Result.UNAVAILABLE, Optional.empty(), 0), sweep());
    assertEquals(before, operations());
    assertEquals(100, ledger.balance(bank));
  }

  @Test
  void aClosedLedgerOrAnOverflowingTotalRecordsNothing() throws Exception {
    Money.fund(pouch, Long.MAX_VALUE);
    ledger.post(
        "more", "staff:test", List.of(new Leg(AccountIds.TAX_VOID, -1), new Leg(bank, 1)));
    deathId = identity.kill(dead);
    long before = operations();
    assertEquals(new Ledger.Sweep(Result.REFUSED, Optional.empty(), 0), sweep());
    assertEquals(before, operations());
    ledger.close();
    assertEquals(Result.UNAVAILABLE, sweep().result());
    assertEquals(List.of(), ledger.estatePostings(deathId));
  }

  @Test
  void theRecipientMustBeALedgerAccount() {
    deathId = identity.kill(dead);
    assertThrows(
        IllegalArgumentException.class,
        () -> ledger.sweepEstate(deathId, dead.characterId(), "player:alex"));
  }

  @Test
  void otherPostingsCannotUseEstateReasonsOrOperationIds() throws Exception {
    Money.fund(bank, 100);
    deathId = identity.kill(dead);
    List<Leg> drain = List.of(new Leg(bank, -100), new Leg(heirBank, 100));
    long before = operations();
    assertEquals(Result.UNAVAILABLE, ledger.post("x", "estate:" + deathId, drain));
    assertEquals(Result.UNAVAILABLE, ledger.post("estate:" + deathId + ":1", "earn", drain));
    assertEquals(before, operations());
    assertEquals(100, ledger.balance(bank));
    assertTrue(Ledger.reserved("estate:"));
    assertFalse(Ledger.reserved("staff:estate"));
    assertFalse(Ledger.reserved(null));
    // The staff exemption still debits a dead character.
    assertEquals(Result.APPLIED, ledger.post("fix", "staff:fix", drain));
    assertEquals(
        new Ledger.Sweep(Result.APPLIED, Optional.empty(), 0), sweep(), "the sweep finds nothing");
  }
}
