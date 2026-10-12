package net.tfminecraft.denareconomy.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import net.tfminecraft.denareconomy.testing.FakeIdentity;
import net.tfminecraft.tlibs.character.CharacterIdentity;
import net.tfminecraft.tlibs.character.CharacterRef;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class AccountIdsTest {
  final UUID id = UUID.randomUUID();

  @Test
  void buildsAndParsesEveryKindOfAccount() {
    assertEquals(
        new AccountIds.Parts("char", null, id.toString(), "pouch"),
        AccountIds.parse(AccountIds.pouch(id)));
    assertEquals(
        new AccountIds.Parts("char", null, id.toString(), "bank"),
        AccountIds.parse(AccountIds.bank(id)));
    assertEquals(
        new AccountIds.Parts("legacy", id.toString(), null, "pouch"),
        AccountIds.parse(AccountIds.legacyPouch(id)));
    assertEquals(
        new AccountIds.Parts("legacy", id.toString(), null, "bank"),
        AccountIds.parse(AccountIds.legacyBank(id)));
    assertEquals(
        new AccountIds.Parts("guild", id.toString(), null, null),
        AccountIds.parse(AccountIds.guild(id)));
    assertEquals(
        new AccountIds.Parts("guildtax", id.toString(), null, null),
        AccountIds.parse(AccountIds.guildTax(id)));
    assertEquals(
        new AccountIds.Parts("server", null, null, "mint"), AccountIds.parse(AccountIds.MINT));
    assertEquals(
        new AccountIds.Parts("server", null, null, "tax-void"),
        AccountIds.parse(AccountIds.TAX_VOID));
  }

  @Test
  void rejectsAnythingElse() {
    for (String bad :
        new String[] {
          null,
          "",
          "char:" + id,
          "char:" + id + ":purse",
          "guild:Rome",
          "server:bank",
          "CHAR:" + id + ":pouch",
          "char:" + id.toString().toUpperCase() + ":pouch",
          AccountIds.pouch(id) + " "
        }) {
      assertThrows(IllegalArgumentException.class, () -> AccountIds.parse(bad), String.valueOf(bad));
    }
  }

  @Test
  void onlySystemAccountsAndTreasuriesMayGoNegative() {
    assertTrue(AccountIds.mayGoNegative(AccountIds.MINT));
    assertTrue(AccountIds.mayGoNegative(AccountIds.TAX_VOID));
    assertTrue(AccountIds.mayGoNegative(AccountIds.guild(id)));
    assertFalse(AccountIds.mayGoNegative(AccountIds.guildTax(id)));
    assertFalse(AccountIds.mayGoNegative(AccountIds.pouch(id)));
    assertFalse(AccountIds.mayGoNegative(AccountIds.legacyBank(id)));
  }

  @Test
  void characterAccountsAreFrozenUnlessTheCharacterIsKnownToBeAlive() {
    MockBukkit.mock();
    try {
      FakeIdentity identity = FakeIdentity.register();
      CharacterRef living = identity.character(UUID.randomUUID());
      CharacterRef dead = identity.character(UUID.randomUUID());
      identity.kill(dead);
      assertTrue(AccountIds.frozen(AccountIds.pouch(dead.characterId())));
      assertTrue(AccountIds.frozen(AccountIds.bank(dead.characterId())));
      assertFalse(AccountIds.frozen(AccountIds.pouch(living.characterId())));
      assertTrue(AccountIds.frozen(AccountIds.pouch(UUID.randomUUID())), "Unknown character");
      assertFalse(AccountIds.frozen(AccountIds.legacyPouch(dead.ownerId())));
      assertFalse(AccountIds.frozen(AccountIds.MINT));
      Bukkit.getServicesManager().unregister(CharacterIdentity.class, identity);
      assertTrue(AccountIds.frozen(AccountIds.pouch(living.characterId())), "No provider");
      assertFalse(AccountIds.frozen(AccountIds.guild(UUID.randomUUID())));
    } finally {
      MockBukkit.unmock();
    }
  }
}
