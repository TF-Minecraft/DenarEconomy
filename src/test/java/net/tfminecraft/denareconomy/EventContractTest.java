package net.tfminecraft.denareconomy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.tfminecraft.denareconomy.data.Account;
import net.tfminecraft.denareconomy.event.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class EventContractTest {
  @Test
  void materialDepositExposesPayloadAndCancellation() {
    Player player = mock(Player.class);
    ItemStack item = mock(ItemStack.class);
    var event = new PlayerDepositMaterialsEvent(player, item);
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
    var event = new PlayerBankPulseEvent(player);
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
    var event = new PlayerEarnMoneyEvent("Alex", 10.);
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
}
