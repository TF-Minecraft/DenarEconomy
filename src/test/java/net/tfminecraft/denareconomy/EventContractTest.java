package net.tfminecraft.denareconomy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.tfminecraft.denareconomy.data.Account;
import net.tfminecraft.denareconomy.event.*;
import net.tfminecraft.tlibs.character.CharacterRef;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class EventContractTest {
  final CharacterRef character = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());

  @Test
  void materialDepositExposesPayloadAndCancellation() {
    Player player = mock(Player.class);
    ItemStack item = mock(ItemStack.class);
    var event = new PlayerDepositMaterialsEvent(player, item, character);
    assertSame(character, event.getCharacter());
    assertSame(player, event.getPlayer());
    assertSame(item, event.getItem());
    assertFalse(event.isCancelled());
    event.setCancelled(true);
    assertTrue(event.isCancelled());
    event.setCancelled(false);
    assertFalse(event.isCancelled());
    assertSame(PlayerDepositMaterialsEvent.getHandlerList(), event.getHandlers());
  }

  @Test
  void bankPulseExposesPlayerAndCancellation() {
    Player player = mock(Player.class);
    var event = new PlayerBankPulseEvent(player, character);
    assertSame(character, event.getCharacter());
    assertSame(player, event.getPlayer());
    assertFalse(event.isCancelled());
    event.setCancelled(true);
    assertTrue(event.isCancelled());
    event.setCancelled(false);
    assertFalse(event.isCancelled());
    assertSame(PlayerBankPulseEvent.getHandlerList(), event.getHandlers());
  }

  @Test
  void earningsAllowListenersToAdjustTax() {
    var event = new PlayerEarnMoneyEvent("Alex", 10., character);
    assertSame(character, event.getCharacter());
    assertEquals("Alex", event.getPlayer());
    assertEquals(10, event.getAmount());
    assertEquals(0, event.getTax());
    event.setAmount(10);
    assertEquals(10, event.getTax(), "Explicit 100% tax must differ from an untouched event");
    event.setAmount(1.25);
    assertEquals(1.25, event.getAmount());
    assertEquals(1.25, event.getTax());
    assertSame(PlayerEarnMoneyEvent.getHandlerList(), event.getHandlers());
  }

  @Test
  void accountsPreserveTaxPolicyAndRoundChanges() {
    Account normal = new Account(1.005);
    assertTrue(normal.isTaxable());
    assertEquals(1.01, normal.getBal());
    Account exempt = new Account(1.005, false);
    assertFalse(exempt.isTaxable());
    assertEquals(1.01, exempt.getBal());
    exempt.setBal(3);
    exempt.change(.125);
    assertEquals(3.13, exempt.getBal());
  }

  @Test
  void snapshotsAreReadOnly() {
    Account snapshot = Account.snapshot(1234, false);
    assertEquals(12.34, snapshot.getBal());
    assertFalse(snapshot.isTaxable());
    Account writable = new Account(5);
    assertThrows(UnsupportedOperationException.class, () -> snapshot.setBal(1));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.change(1));
    var amount = new java.math.BigDecimal("1.00");
    assertThrows(UnsupportedOperationException.class, () -> snapshot.transferTo(writable, amount));
    assertThrows(UnsupportedOperationException.class, () -> writable.transferTo(snapshot, amount));
    assertFalse(snapshot.transferTo(null, amount));
    assertEquals(12.34, snapshot.getBal());
    assertEquals(5, writable.getBal());
    var data = net.tfminecraft.denareconomy.data.PlayerData.snapshot(character.ownerId(), 100, 250);
    assertEquals(character.ownerId(), data.getId());
    assertEquals(1, data.getPouch().getBal());
    assertTrue(data.getPouch().isTaxable());
    assertEquals(2.5, data.getBank().getBal());
    assertFalse(data.getBank().isTaxable());
  }
}
