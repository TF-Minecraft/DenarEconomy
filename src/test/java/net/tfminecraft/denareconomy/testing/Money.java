package net.tfminecraft.denareconomy.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.denareconomy.ledger.AccountIds;
import net.tfminecraft.denareconomy.ledger.Ledger;

/** A real ledger in a temporary folder, installed as DenarEconomy's ledger. */
public final class Money {
  private Money() {}

  public static Ledger open(Path folder) {
    Ledger ledger =
        Ledger.open(
            folder.resolve("ledger.db").toFile(),
            Logger.getLogger("DenarEconomyTest"),
            AccountIds::deadCharacter);
    DenarEconomy.setLedger(ledger);
    return ledger;
  }

  /** Closes the installed ledger. Call before MockBukkit.unmock(). */
  public static void close() {
    DenarEconomy.getLedger().close();
    DenarEconomy.setLedger(Ledger.unavailable());
  }

  /** Mints money into an account. */
  public static void fund(String account, long cents) {
    assertEquals(
        Result.APPLIED,
        Denars.transfer("fund:" + UUID.randomUUID(), "staff:test", Denars.MINT, account, cents));
  }
}
