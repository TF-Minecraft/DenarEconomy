package net.tfminecraft.denareconomy.api;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.tfminecraft.denareconomy.event.PlayerEarnMoneyEvent;
import net.tfminecraft.denareconomy.ledger.AccountIds;
import net.tfminecraft.denareconomy.testing.FakeIdentity;
import net.tfminecraft.denareconomy.testing.Money;
import net.tfminecraft.tlibs.character.CharacterRef;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class DenarsTest {
  @TempDir Path folder;
  ServerMock server;
  FakeIdentity identity;
  Player player;
  CharacterRef character;
  final List<PlayerEarnMoneyEvent> earned = new ArrayList<>();

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    Money.open(folder);
    identity = FakeIdentity.register();
    player = server.addPlayer("Alex");
    character = identity.play(player);
    Bukkit.getPluginManager()
        .registerEvent(
            PlayerEarnMoneyEvent.class,
            new Listener() {},
            EventPriority.NORMAL,
            (listener, event) -> earned.add((PlayerEarnMoneyEvent) event),
            MockBukkit.createMockPlugin());
  }

  @AfterEach
  void tearDown() {
    Money.close();
    MockBukkit.unmock();
  }

  void taxAt(int percent, String account) {
    TaxPolicy policy = (earner, gross) -> Optional.of(new TaxLeg(account, gross * percent / 100));
    Bukkit.getServicesManager()
        .register(TaxPolicy.class, policy, MockBukkit.createMockPlugin(), ServicePriority.High);
  }

  @Test
  void readyNeedsAnOpenLedgerAndACharacterProvider() {
    assertTrue(Denars.ready());
    Bukkit.getServicesManager().unregister(net.tfminecraft.tlibs.character.CharacterIdentity.class, identity);
    assertFalse(Denars.ready());
    FakeIdentity.register();
    Money.close();
    assertFalse(Denars.ready());
  }

  @Test
  void resolvesTheActiveCharacterAndItsAccounts() {
    assertEquals(Optional.of(character), Denars.active(player));
    identity.stop(player);
    assertEquals(Optional.empty(), Denars.active(player));
    UUID id = character.characterId();
    assertEquals("char:" + id + ":pouch", Denars.pouch(character));
    assertEquals("char:" + id + ":bank", Denars.bank(character));
    assertEquals("guild:" + id, Denars.guild(id));
    assertEquals("guildtax:" + id, Denars.guildTax(id));
    assertEquals(AccountIds.MINT, Denars.MINT);
    assertEquals(AccountIds.TAX_VOID, Denars.TAX_VOID);
  }

  @Test
  void transfersPostAndReportTheirStatus() {
    Money.fund(Denars.pouch(character), 500);
    assertEquals(
        Result.APPLIED,
        Denars.transfer("t", "deposit", Denars.pouch(character), Denars.bank(character), 200));
    assertEquals(300, Denars.balance(Denars.pouch(character)));
    assertEquals(200, Denars.balance(Denars.bank(character)));
    assertEquals(Optional.of(Result.APPLIED), Denars.status("t"));
    assertEquals(
        Result.REFUSED,
        Denars.post(
            "p",
            "pay",
            List.of(new Leg(Denars.pouch(character), -301), new Leg(Denars.MINT, 301))));
    assertThrows(
        IllegalArgumentException.class,
        () -> Denars.transfer("z", "r", Denars.MINT, Denars.pouch(character), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> Denars.transfer("z", "r", Denars.MINT, Denars.pouch(character), -5));
  }

  @Test
  void estateReasonsAndOperationIdsAreRefusedBeforeAnythingIsRecorded() {
    String pouch = Denars.pouch(character);
    Money.fund(pouch, 500);
    identity.kill(character);
    List<TaxPolicy> asked = new ArrayList<>();
    TaxPolicy policy =
        (earner, gross) -> {
          asked.add(null);
          return Optional.empty();
        };
    Bukkit.getServicesManager()
        .register(TaxPolicy.class, policy, MockBukkit.createMockPlugin(), ServicePriority.High);
    String heir = Denars.bank(identity.character(UUID.randomUUID()));
    String estate = "estate:" + UUID.randomUUID();
    assertEquals(
        Result.UNAVAILABLE,
        Denars.post("a", estate, List.of(new Leg(pouch, -500), new Leg(heir, 500))));
    assertEquals(Result.UNAVAILABLE, Denars.transfer("b", estate, pouch, heir, 500));
    assertEquals(Result.UNAVAILABLE, Denars.transfer(estate + ":1", "will", pouch, heir, 500));
    assertEquals(Result.UNAVAILABLE, Denars.earn(estate + ":2", character, heir, 500, true));
    assertEquals(
        Result.UNAVAILABLE, Denars.trade("c", estate, pouch, character, heir, 500, true));
    assertEquals(
        Result.UNAVAILABLE, Denars.trade(estate + ":3", "sale", pouch, character, heir, 500, false));
    assertTrue(asked.isEmpty(), "the tax policy is not consulted");
    for (String opId : List.of("a", "b", "c", estate + ":1", estate + ":2", estate + ":3")) {
      assertEquals(Optional.empty(), Denars.status(opId));
    }
    assertEquals(500, Denars.balance(pouch));
    assertEquals(0, Denars.balance(heir));
    assertThrows(
        IllegalArgumentException.class,
        () -> Denars.trade(null, "sale", heir, character, pouch, 1, false));
    // Staff may still correct a dead character's accounts.
    assertEquals(Result.APPLIED, Denars.transfer("d", "staff:move fix", pouch, heir, 500));
  }

  @Test
  void untaxedEarningsAreCreditedInFullWithoutAnEvent() {
    taxAt(10, Denars.guildTax(UUID.randomUUID()));
    assertEquals(Result.APPLIED, Denars.earn("e", character, Denars.bank(character), 1000, false));
    assertEquals(1000, Denars.balance(Denars.bank(character)));
    assertEquals(-1000, Denars.balance(Denars.MINT));
    assertTrue(earned.isEmpty());
  }

  @Test
  void taxableEarningsWithoutAPolicyAreUntaxedButAnnounced() {
    assertEquals(0, Denars.taxFor(character, 1000));
    assertEquals(Result.APPLIED, Denars.earn("e", character, Denars.pouch(character), 1000, true));
    assertEquals(1000, Denars.balance(Denars.pouch(character)));
    PlayerEarnMoneyEvent event = earned.getFirst();
    assertEquals("Alex", event.getPlayer());
    assertEquals(10.0, event.getAmount());
    assertSame(character, event.getCharacter());
  }

  @Test
  void taxableEarningsPostTheTaxLegInTheSameOperation() {
    String holding = Denars.guildTax(UUID.randomUUID());
    taxAt(25, holding);
    assertEquals(250, Denars.taxFor(character, 1000));
    assertEquals(Result.APPLIED, Denars.earn("e", character, Denars.pouch(character), 1000, true));
    assertEquals(750, Denars.balance(Denars.pouch(character)));
    assertEquals(250, Denars.balance(holding));
    assertEquals(-1000, Denars.balance(Denars.MINT));
    assertEquals(1, earned.size());
    // A retry applies nothing more.
    assertEquals(Result.APPLIED, Denars.earn("e", character, Denars.pouch(character), 1000, true));
    assertEquals(750, Denars.balance(Denars.pouch(character)));
  }

  @Test
  void aPolicyMayDeclineOrTakeEverythingButNoMore() {
    TaxPolicy none = (earner, gross) -> Optional.empty();
    Bukkit.getServicesManager()
        .register(TaxPolicy.class, none, MockBukkit.createMockPlugin(), ServicePriority.High);
    assertEquals(Result.APPLIED, Denars.earn("a", character, Denars.pouch(character), 100, true));
    assertEquals(100, Denars.balance(Denars.pouch(character)));
    Bukkit.getServicesManager().unregister(TaxPolicy.class, none);

    taxAt(100, Denars.TAX_VOID);
    assertEquals(Result.APPLIED, Denars.earn("b", character, Denars.pouch(character), 100, true));
    assertEquals(100, Denars.balance(Denars.pouch(character)));
    assertEquals(100, Denars.balance(Denars.TAX_VOID));
    Bukkit.getServicesManager().unregister(TaxPolicy.class, Bukkit.getServicesManager().load(TaxPolicy.class));

    taxAt(150, Denars.TAX_VOID);
    assertThrows(
        IllegalStateException.class,
        () -> Denars.earn("c", character, Denars.pouch(character), 100, true));
    assertEquals(Optional.empty(), Denars.status("c"));
    assertThrows(IllegalArgumentException.class, () -> new TaxLeg(Denars.TAX_VOID, -1));
  }

  @Test
  void tradesPayTheSellerNetOfTaxAndRefuseAnUnfundedBuyer() {
    Player seller = server.addPlayer("Sam");
    CharacterRef shopkeeper = identity.play(seller);
    String holding = Denars.guildTax(UUID.randomUUID());
    taxAt(10, holding);
    String buyer = Denars.pouch(character);
    assertEquals(
        Result.REFUSED,
        Denars.trade("shop:1", "shop", buyer, shopkeeper, Denars.bank(shopkeeper), 500, true));
    assertTrue(earned.isEmpty());
    Money.fund(buyer, 1000);
    assertEquals(
        Result.APPLIED,
        Denars.trade("shop:2", "shop", buyer, shopkeeper, Denars.bank(shopkeeper), 500, true));
    assertEquals(500, Denars.balance(buyer));
    assertEquals(450, Denars.balance(Denars.bank(shopkeeper)));
    assertEquals(50, Denars.balance(holding));
    assertEquals("Sam", earned.getFirst().getPlayer());
    assertEquals(
        Result.APPLIED,
        Denars.trade("shop:3", "shop", buyer, shopkeeper, Denars.bank(shopkeeper), 500, false));
    assertEquals(950, Denars.balance(Denars.bank(shopkeeper)));
    assertEquals(1, earned.size());
  }

  @Test
  void aClosedLedgerMakesEveryCallUnavailable() {
    Money.close();
    assertEquals(
        Result.UNAVAILABLE, Denars.earn("e", character, Denars.pouch(character), 100, true));
    assertTrue(earned.isEmpty());
    assertEquals(0, Denars.balance(Denars.pouch(character)));
    assertEquals(Optional.empty(), Denars.status("e"));
  }

  @Test
  void aDeadCharactersMoneyStaysFrozenWhenTheProviderGoesAway() {
    String pouch = Denars.pouch(character);
    Money.fund(pouch, 500);
    identity.kill(character);
    Bukkit.getServicesManager()
        .unregister(net.tfminecraft.tlibs.character.CharacterIdentity.class, identity);
    assertEquals(Result.REFUSED, Denars.transfer("spend", "pay", pouch, Denars.MINT, 100));
    assertEquals(Result.APPLIED, Denars.transfer("wage", "wage", Denars.MINT, pouch, 100));
    assertEquals(
        Result.UNAVAILABLE, Denars.transfer("estate", "estate:heir", pouch, Denars.TAX_VOID, 100));
    assertEquals(Result.APPLIED, Denars.transfer("fix", "staff:fix", pouch, Denars.MINT, 100));
    assertEquals(500, Denars.balance(pouch));
  }

  @Test
  void everyFacadeCallChecksTheThreadBeforeAnyLookupOrPolicy() throws Exception {
    List<CharacterRef> asked = new ArrayList<>();
    TaxPolicy policy =
        (earner, gross) -> {
          asked.add(earner);
          return Optional.empty();
        };
    Bukkit.getServicesManager()
        .register(TaxPolicy.class, policy, MockBukkit.createMockPlugin(), ServicePriority.High);
    String pouch = Denars.pouch(character);
    List<Runnable> calls =
        List.of(
            Denars::ready,
            Denars::taxReady,
            () -> Denars.active(player),
            () -> Denars.balance(pouch),
            () -> Denars.post("p", "r", List.of(new Leg(Denars.MINT, -1), new Leg(pouch, 1))),
            () -> Denars.transfer("t", "r", Denars.MINT, pouch, 1),
            () -> Denars.status("t"),
            () -> Denars.taxFor(character, 100),
            () -> Denars.earn("e", character, pouch, 100, true),
            () -> Denars.trade("s", "shop", Denars.MINT, character, pouch, 100, true));
    for (Runnable call : calls) {
      java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
          new java.util.concurrent.atomic.AtomicReference<>();
      Thread worker =
          new Thread(
              () -> {
                try {
                  call.run();
                } catch (Throwable e) {
                  thrown.set(e);
                }
              });
      worker.start();
      worker.join();
      assertInstanceOf(IllegalStateException.class, thrown.get());
    }
    assertTrue(asked.isEmpty());
    assertEquals(Optional.empty(), Denars.status("e"));
    assertEquals("guild:" + character.ownerId(), Denars.guild(character.ownerId()));
  }

  @Test
  void taxablePaymentsWaitForTheInstalledFactionPolicyWithoutRecordingRefusal() {
    var factions = MockBukkit.createMockPlugin("SimpleFactions");
    assertFalse(Denars.taxReady());
    assertEquals(Result.UNAVAILABLE, Denars.earn("waiting", character, Denars.pouch(character), 100, true));
    assertEquals(Result.UNAVAILABLE, Denars.trade("shop-waiting", "shop", Denars.MINT,
        character, Denars.pouch(character), 100, true));
    assertEquals(Optional.empty(), Denars.status("waiting"));
    assertEquals(0, Denars.balance(Denars.pouch(character)));
    taxAt(10, Denars.TAX_VOID);
    assertTrue(Denars.taxReady());
    assertEquals(Result.APPLIED, Denars.earn("waiting", character, Denars.pouch(character), 100, true));
    assertEquals(90, Denars.balance(Denars.pouch(character)));
    server.getPluginManager().disablePlugin(factions);
    assertFalse(Denars.taxReady());
    assertEquals(Result.UNAVAILABLE, Denars.earn("disabled", character, Denars.pouch(character), 100, true));
    assertEquals(Result.APPLIED, Denars.earn("untaxed", character, Denars.pouch(character), 100, false));
    assertEquals(190, Denars.balance(Denars.pouch(character)));
  }
}
