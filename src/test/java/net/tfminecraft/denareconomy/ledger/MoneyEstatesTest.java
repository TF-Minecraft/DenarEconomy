package net.tfminecraft.denareconomy.ledger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.denareconomy.ledger.AccountIds;
import net.tfminecraft.denareconomy.ledger.Ledger;
import net.tfminecraft.denareconomy.testing.FakeEstates;
import net.tfminecraft.denareconomy.testing.FakeIdentity;
import net.tfminecraft.denareconomy.testing.Money;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.DeathGuild;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.EstateService;
import net.tfminecraft.tlibs.character.estate.EstateState;
import net.tfminecraft.tlibs.character.estate.Estates;
import net.tfminecraft.tlibs.character.estate.GuildLookup;
import net.tfminecraft.tlibs.character.estate.GuildRef;
import net.tfminecraft.tlibs.character.estate.Holdings;
import net.tfminecraft.tlibs.character.estate.Outcome;
import net.tfminecraft.tlibs.character.estate.Recipient;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class MoneyEstatesTest {
  @TempDir Path folder;
  ServerMock server;
  FakeIdentity identity;
  FakeEstates estates;
  Ledger ledger;
  MoneyEstates money;
  final Logger log = Logger.getAnonymousLogger();
  final java.util.List<LogRecord> logs = new java.util.ArrayList<>();
  CharacterRef dead;
  UUID deathId;
  final UUID guild = UUID.randomUUID();
  final UUID realm = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    ledger = Money.open(folder);
    identity = FakeIdentity.register();
    estates = FakeEstates.register();
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
    money = new MoneyEstates(log);
    dead = identity.character(UUID.randomUUID());
  }

  @AfterEach
  void tearDown() {
    Money.close();
    MockBukkit.unmock();
  }

  String pouch() {
    return AccountIds.pouch(dead.characterId());
  }

  String bank() {
    return AccountIds.bank(dead.characterId());
  }

  EstateDecision decide(Recipient recipient) {
    return estates.decide(
        dead, deathId, recipient, Optional.of(guild), Optional.of(realm), 0);
  }

  /** Funds the character, then kills it. */
  void dies(long pouch, long bank) {
    if (pouch > 0) Money.fund(pouch(), pouch);
    if (bank > 0) Money.fund(bank(), bank);
    deathId = identity.kill(dead);
  }

  static Outcome done(long cents, String... opIds) {
    return Outcome.done(
        Map.of("cents", (int) cents, "postings", opIds.length), List.of(opIds));
  }

  String op(int n) {
    return "estate:" + deathId + ":" + n;
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
  void aLiveDeathGuildReceivesThePouchAndBank() {
    dies(300, 1_000);
    estates.live(guild);
    EstateDecision decision = decide(Recipient.account(AccountIds.guild(guild)));
    assertEquals(MoneyEstates.ID, money.id());
    assertEquals(done(1_300, op(1)), money.settle(decision));
    assertEquals(1_300, ledger.balance(AccountIds.guild(guild)));
    assertEquals(0, ledger.balance(pouch()) + ledger.balance(bank()));
    assertEquals(done(1_300, op(1)), money.settle(decision), "settling again moves nothing");
    assertEquals(1_300, ledger.balance(AccountIds.guild(guild)));
    assertEquals(0, total());
  }

  @Test
  void anHeirsBankReceivesTheMoneyAndEmptyAccountsAreDoneToo() {
    dies(0, 0);
    String heir = AccountIds.bank(UUID.randomUUID());
    EstateDecision decision = decide(Recipient.account(heir));
    assertEquals(done(0), money.settle(decision), "nothing to move is still a completed sweep");
    Money.fund(pouch(), 25);
    assertEquals(done(25, op(1)), money.settle(decision));
    assertEquals(25, ledger.balance(heir));
    assertTrue(estates.guildCalls.isEmpty(), "a character recipient needs no guild answer");
  }

  @Test
  void frozenMoneyStaysWithTheDeadCharacter() {
    dies(100, 200);
    assertEquals(Outcome.done(), money.settle(decide(Recipient.FROZEN)));
    assertEquals(300, ledger.balance(pouch()) + ledger.balance(bank()));
  }

  @Test
  void theHourlySweepForwardsLateCreditsAndReportsOnlyChanges() {
    Plugin plugin = MockBukkit.createMockPlugin();
    MoneyEstates registered = MoneyEstates.register(plugin);
    assertSame(registered, Bukkit.getServicesManager().load(EstateParticipant.class));
    assertEquals(List.of(registered), Estates.participants());
    dies(500, 0);
    estates.live(guild);
    decide(Recipient.account(AccountIds.guild(guild)));
    server.getScheduler().performOneTick();
    assertEquals(
        List.of(new FakeEstates.Report(deathId, "denareconomy", 0, done(500, op(1)))),
        estates.reports);
    server.getScheduler().performTicks(MoneyEstates.HOUR_TICKS);
    assertEquals(1, estates.reports.size(), "an unchanged outcome is not reported again");
    // A wage arrives after the decision; the next hourly sweep forwards it.
    Money.fund(bank(), 75);
    server.getScheduler().performTicks(MoneyEstates.HOUR_TICKS);
    assertEquals(done(575, op(1), op(2)), estates.reports.getLast().outcome());
    assertEquals(575, ledger.balance(AccountIds.guild(guild)));
    assertEquals(0, total());
  }

  @Test
  void aRevivedCharacterKeepsWhatArrivesAfterTheRevival() {
    dies(500, 0);
    estates.live(guild);
    EstateDecision decision = decide(Recipient.account(AccountIds.guild(guild)));
    money.reconcile();
    assertEquals(500, ledger.balance(AccountIds.guild(guild)));
    identity.revive(dead);
    Money.fund(bank(), 80);
    money.reconcile();
    assertEquals(1, estates.reports.size(), "a guard that fails is not reported");
    assertEquals(
        Outcome.retry("The estate guard does not hold"), money.settle(decision));
    assertEquals(80, ledger.balance(bank()));
    assertEquals(500, ledger.balance(AccountIds.guild(guild)), "the committed sweep stays");
    // A later death is a new estate; the old decision no longer moves anything.
    identity.kill(dead);
    money.reconcile();
    assertEquals(80, ledger.balance(bank()));
  }

  @Test
  void aRevertedOrRevisedEstateIsNotSwept() {
    dies(500, 0);
    estates.live(guild);
    EstateDecision decision = decide(Recipient.account(AccountIds.guild(guild)));
    estates.states.put(dead.characterId(), EstateState.REVERTED);
    money.reconcile();
    assertEquals(Outcome.Kind.RETRY, money.settle(decision).kind());
    estates.states.put(dead.characterId(), EstateState.DECIDED);
    estates.decide(
        dead, deathId, Recipient.FROZEN, Optional.of(guild), Optional.of(realm), 1);
    assertEquals(Outcome.Kind.RETRY, money.settle(decision).kind(), "revision 0 is stale");
    assertEquals(500, ledger.balance(pouch()));
    assertTrue(estates.reports.isEmpty());
  }

  @Test
  void aGoneDeathGuildsMoneyGoesToItsLiveRealm() {
    dies(0, 900);
    estates.live(realm);
    assertEquals(done(900, op(1)), money.settle(decide(Recipient.account(AccountIds.guild(guild)))));
    assertEquals(900, ledger.balance(AccountIds.guild(realm)));
    assertEquals(0, ledger.balance(AccountIds.guild(guild)));
    assertEquals(List.of(guild, realm), estates.guildCalls);
  }

  @Test
  void aGoneGuildWithoutALiveRealmPostsNothingAndIsBlocked() {
    dies(0, 900);
    Recipient toGuild = Recipient.account(AccountIds.guild(guild));
    assertEquals(
        Outcome.blocked("Guild " + guild + " and its realm " + realm + " are gone"),
        money.settle(decide(toGuild)));
    estates.guilds.put(realm, GuildLookup.UNAVAILABLE);
    assertEquals(Outcome.Kind.RETRY, money.settle(decide(toGuild)).kind());
    // Without a realm, as the capital's own guild, or for a guild staff chose, nothing follows.
    String noRealm = "Guild " + guild + " is gone and has no realm to take its money";
    EstateDecision unknownRealm =
        estates.decide(dead, deathId, toGuild, Optional.of(guild), Optional.empty(), 0);
    estates.death = DeathGuild.NONE;
    assertEquals(Outcome.blocked(noRealm), money.settle(unknownRealm));
    estates.death = DeathGuild.guild(UUID.randomUUID(), false, Optional.empty(), Optional.of(realm));
    assertEquals(Outcome.blocked(noRealm), money.settle(unknownRealm), "another guild's realm");
    estates.death = DeathGuild.guild(guild, false, Optional.empty(), Optional.empty());
    assertEquals(Outcome.blocked(noRealm), money.settle(unknownRealm), "still unknown");
    assertEquals(
        Outcome.blocked(noRealm),
        money.settle(
            estates.decide(dead, deathId, toGuild, Optional.of(guild), Optional.of(guild), 0)));
    estates.live(realm);
    assertEquals(
        Outcome.blocked(noRealm),
        money.settle(
            estates.decide(
                dead, deathId, toGuild, Optional.of(UUID.randomUUID()), Optional.of(realm), 0)));
    assertEquals(900, ledger.balance(bank()));
    assertTrue(ledger.estatePostings(deathId).isEmpty(), "no estate operation is recorded");
  }

  @Test
  void aRealmUnknownAtTheDecisionIsAskedForAgainAndItsOwnTreasuryUsed() {
    dies(0, 900);
    EstateDecision decision =
        estates.decide(
            dead,
            deathId,
            Recipient.account(AccountIds.guild(guild)),
            Optional.of(guild),
            Optional.empty(),
            0);
    assertEquals(
        Outcome.retry("SimpleFactions cannot say which realm guild " + guild + " was in"),
        money.settle(decision));
    estates.death = DeathGuild.guild(guild, false, Optional.empty(), Optional.of(realm));
    String treasury = AccountIds.guild(UUID.randomUUID());
    estates.guilds.put(
        realm,
        GuildLookup.live(
            new GuildRef(realm, "Realm", Optional.empty(), Optional.empty(), treasury)));
    assertEquals(done(900, op(1)), money.settle(decision));
    assertEquals(900, ledger.balance(treasury), "the treasury SimpleFactions names");
    estates.guilds.put(
        realm,
        GuildLookup.live(
            new GuildRef(realm, "Realm", Optional.empty(), Optional.empty(), "nowhere")));
    assertEquals(
        Outcome.blocked("The money recipient nowhere is not a ledger account"),
        money.settle(decision));
  }

  @Test
  void onlyCharacterAccountsAndGuildTreasuriesReceiveEstateMoney() {
    dies(0, 900);
    for (String account :
        List.of(
            AccountIds.guildTax(guild),
            AccountIds.legacyBank(UUID.randomUUID()),
            AccountIds.MINT)) {
      assertEquals(
          Outcome.blocked("The money recipient " + account + " is not a character or guild"),
          money.settle(decide(Recipient.account(account))));
    }
    assertTrue(estates.guildCalls.isEmpty());
    assertEquals(done(900, op(1)), money.settle(decide(Recipient.account(pouch()))));
    assertEquals(900, ledger.balance(pouch()), "the character's own pouch, as staff may assign");
  }

  @Test
  void anOutcomeIsReportedAgainUntilTheServiceHasTakenIt() {
    dies(0, 500);
    String heir = AccountIds.bank(UUID.randomUUID());
    estates.live(guild);
    EstateDecision decision = decide(Recipient.account(AccountIds.guild(guild)));
    estates.reportFailure = new IllegalStateException("estate store broken");
    money.reconcile();
    assertEquals(500, ledger.balance(AccountIds.guild(guild)), "the money moved");
    estates.reportFailure = null;
    money.reconcile();
    assertEquals(
        List.of(new FakeEstates.Report(deathId, "denareconomy", 0, done(500, op(1)))),
        estates.reports,
        "the failed report is sent again");
    // RPCharacters' own call does not count as a report, so the next pass reports it once.
    Money.fund(bank(), 20);
    assertEquals(done(520, op(1), op(2)), money.settle(decision));
    money.reconcile();
    money.reconcile();
    assertEquals(2, estates.reports.size());
    // A report nobody received is sent again too.
    Money.fund(bank(), 30);
    estates.onGuild = estates::unregisterService;
    money.reconcile();
    assertEquals(2, estates.reports.size());
    estates.onGuild = null;
    estates.registerService();
    money.reconcile();
    assertEquals(done(550, op(1), op(2), op(3)), estates.reports.getLast().outcome());
    assertEquals(0, ledger.balance(heir));
  }

  @Test
  void anUnansweredGuildLookupSkipsTheSweep() {
    dies(0, 900);
    estates.guilds.put(guild, GuildLookup.UNAVAILABLE);
    EstateDecision decision = decide(Recipient.account(AccountIds.guild(guild)));
    assertEquals(Outcome.Kind.RETRY, money.settle(decision).kind());
    estates.unregisterFallback();
    assertEquals(
        Outcome.retry("SimpleFactions cannot say whether guild " + guild + " still exists"),
        money.settle(decision));
    assertEquals(900, ledger.balance(bank()));
    assertTrue(ledger.estatePostings(deathId).isEmpty());
  }

  @Test
  void ledgerAndRecipientProblemsAreRetriedOrListed() {
    dies(0, 900);
    assertEquals(
        Outcome.blocked("The money recipient nowhere is not a ledger account"),
        money.settle(decide(Recipient.account("nowhere"))));
    // An overflowing credit is refused and retried with the next number.
    ledger.post(
        "full",
        "staff:test",
        List.of(
            new net.tfminecraft.denareconomy.api.Leg(AccountIds.TAX_VOID, -Long.MAX_VALUE),
            new net.tfminecraft.denareconomy.api.Leg(AccountIds.bank(guild), Long.MAX_VALUE)));
    assertEquals(
        Outcome.retry("The ledger refused the sweep " + op(1)),
        money.settle(decide(Recipient.account(AccountIds.bank(guild)))));
    assertEquals(900, ledger.balance(bank()));
    ledger.post(
        "drain",
        "staff:test",
        List.of(
            new net.tfminecraft.denareconomy.api.Leg(AccountIds.bank(guild), -1_000),
            new net.tfminecraft.denareconomy.api.Leg(AccountIds.TAX_VOID, 1_000)));
    assertEquals(
        done(900, op(2)),
        money.settle(decide(Recipient.account(AccountIds.bank(guild)))),
        "the refused number moved nothing and is not counted");
    ledger.close();
    assertEquals(
        Outcome.retry("The ledger or the character directory is unavailable"),
        money.settle(decide(Recipient.account(AccountIds.bank(UUID.randomUUID())))));
  }

  @Test
  void aConflictWhichTheNumberingPreventsWouldBeListedForStaff() {
    dies(0, 900);
    Ledger conflicting = mock(Ledger.class);
    when(conflicting.sweepEstate(any(), any(), any()))
        .thenReturn(new Ledger.Sweep(Result.CONFLICT, Optional.of(op(1)), 0));
    DenarEconomy.setLedger(conflicting);
    try {
      assertEquals(
          Outcome.blocked("Sweep " + op(1) + " conflicts with a stored operation"),
          money.settle(decide(Recipient.account(AccountIds.bank(UUID.randomUUID())))));
      when(conflicting.sweepEstate(any(), any(), any()))
          .thenReturn(new Ledger.Sweep(Result.REFUSED, Optional.empty(), 0));
      assertEquals(
          Outcome.retry("The ledger refused the sweep of an overflowing total"),
          money.settle(decide(Recipient.account(AccountIds.bank(UUID.randomUUID())))));
    } finally {
      DenarEconomy.setLedger(ledger);
    }
  }

  @Test
  void oneFailingEstateDoesNotStopTheOthers() {
    dies(0, 100);
    UUID firstDeath = deathId;
    decide(Recipient.account(AccountIds.guild(guild)));
    CharacterRef other = identity.character(UUID.randomUUID());
    Money.fund(AccountIds.bank(other.characterId()), 40);
    UUID otherDeath = identity.kill(other);
    String heir = AccountIds.bank(UUID.randomUUID());
    estates.decide(other, otherDeath, Recipient.account(heir), Optional.empty(), Optional.empty(), 0);
    estates.failure = new IllegalStateException("guild store broken");
    money.reconcile();
    assertEquals(40, ledger.balance(heir));
    assertEquals(
        List.of(otherDeath), estates.reports.stream().map(FakeEstates.Report::deathId).toList());
    assertTrue(
        logs.stream()
            .anyMatch(
                r -> r.getLevel() == Level.WARNING && r.getMessage().contains(firstDeath.toString())));
  }

  @Test
  void withoutAnEstateServiceThereIsNothingToSweep() {
    Bukkit.getServicesManager().unregister(EstateService.class, estates);
    dies(0, 100);
    decide(Recipient.account(AccountIds.bank(UUID.randomUUID())));
    money.reconcile();
    assertEquals(100, ledger.balance(bank()));
  }

  @Test
  void movedCentsAreCappedForTheEstateRecordButAllTheMoneyMoves() {
    dies(Integer.MAX_VALUE, 5);
    String heir = AccountIds.bank(UUID.randomUUID());
    EstateDecision decision = decide(Recipient.account(heir));
    money.settle(decision);
    Money.fund(bank(), Long.MAX_VALUE / 2);
    assertEquals(
        Outcome.done(
            Map.of("cents", Integer.MAX_VALUE, "postings", 2), List.of(op(1), op(2))),
        money.settle(decision));
    assertEquals(Integer.MAX_VALUE + 5L + Long.MAX_VALUE / 2, ledger.balance(heir));
  }

  @Test
  void describeShowsBothBalances() {
    assertEquals(Holdings.NONE, money.describe(dead.characterId()));
    Money.fund(pouch(), 1_250);
    assertEquals(
        new Holdings(
            Map.of("cents", 1_250), List.of("Money: 12.50 in the pouch, 0.00 in the bank")),
        money.describe(dead.characterId()));
    Money.fund(bank(), Integer.MAX_VALUE);
    assertEquals(
        Map.of("cents", Integer.MAX_VALUE), money.describe(dead.characterId()).counts());
    CharacterRef other = identity.character(UUID.randomUUID());
    Money.fund(AccountIds.bank(other.characterId()), 5);
    assertEquals(Map.of("cents", 5), money.describe(other.characterId()).counts());
  }
}
