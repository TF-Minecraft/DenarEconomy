package net.tfminecraft.denareconomy.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.tfminecraft.denareconomy.accounts.OfflineModifier;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.denareconomy.enums.Accounts;
import net.tfminecraft.denareconomy.ledger.AccountIds;
import net.tfminecraft.denareconomy.managers.MoneyManager;
import net.tfminecraft.denareconomy.managers.PlayerManager;
import net.tfminecraft.denareconomy.testing.FakeIdentity;
import net.tfminecraft.denareconomy.testing.Money;
import net.tfminecraft.tlibs.character.CharacterRef;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

class DatabaseTest {
  @TempDir Path folder;
  ServerMock server;
  FakeIdentity identity;
  PlayerMock alex;
  CharacterRef character;
  final MoneyManager money = new MoneyManager();
  final PlayerManager players = new PlayerManager();

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    Money.open(folder);
    identity = FakeIdentity.register();
    alex = server.addPlayer("Alex");
    character = identity.play(alex);
  }

  @AfterEach
  void tearDown() {
    Money.close();
    MockBukkit.unmock();
  }

  @Test
  void readinessRequiresAnOnlinePlayerWithACurrentCharacter() {
    assertFalse(Database.hasPlayerData(null));
    assertFalse(Database.hasPlayerData(UUID.randomUUID()));
    assertTrue(Database.hasPlayerData(alex.getUniqueId()));
    identity.stop(alex);
    assertFalse(Database.hasPlayerData(alex.getUniqueId()));
    identity.start(alex, character);
    assertTrue(Database.hasPlayerData(alex.getUniqueId()));
    alex.disconnect();
    assertFalse(Database.hasPlayerData(alex.getUniqueId()));
    assertNotNull(new Database());
  }

  @Test
  void capturedCharacterPaymentCannotMoveToTheNextCharacterOrRepeat() {
    CharacterRef second = identity.character(alex.getUniqueId());
    CharacterRef captured = Denars.active(alex).orElseThrow();
    identity.start(alex, second);
    assertEquals(Result.APPLIED, Denars.earn("delayed-A", captured, Denars.pouch(captured), 250, false));
    Denars.earn("current-B", second, Denars.pouch(second), 100, false);
    Denars.earn("delayed-A", captured, Denars.pouch(captured), 250, false);
    assertEquals(250, Denars.balance(Denars.pouch(character)));
    assertEquals(100, Denars.balance(Denars.pouch(second)));
    assertEquals(-350, Denars.balance(Denars.MINT));
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
