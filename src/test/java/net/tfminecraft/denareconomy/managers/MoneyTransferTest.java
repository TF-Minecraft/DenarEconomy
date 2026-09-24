package net.tfminecraft.denareconomy.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.tfminecraft.denareconomy.data.Account;

class MoneyTransferTest {
    @Test
    void repeatedHalfCentDepositsCannotMintMoney() {
        Account pouch = new Account(1.00);
        Account bank = new Account(0.00);
        for (int i = 0; i < 1000; i++) {
            MoneyManager.transfer(pouch, bank, 0.005);
        }
        assertEquals(1.00, pouch.getBal());
        assertEquals(0.00, bank.getBal());
    }

    @Test
    void repeatedHalfCentWithdrawalsCannotMintMoney() {
        Account pouch = new Account(0.00);
        Account bank = new Account(1.00);
        for (int i = 0; i < 1000; i++) {
            MoneyManager.transfer(bank, pouch, 0.005);
        }
        assertEquals(0.00, pouch.getBal());
        assertEquals(1.00, bank.getBal());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.001, 0.005, 0.009, 1.001, -0.01, 0.0, Double.NaN,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void invalidTransfersLeaveBothAccountsUntouched(double amount) {
        Account from = new Account(10.00);
        Account to = new Account(2.00);
        MoneyManager.transfer(from, to, amount);
        assertEquals(10.00, from.getBal());
        assertEquals(2.00, to.getBal());
    }

    @Test
    void insufficientFundsLeaveBothAccountsUntouched() {
        Account from = new Account(0.01);
        Account to = new Account(2.00);
        MoneyManager.transfer(from, to, 0.02);
        assertEquals(0.01, from.getBal());
        assertEquals(2.00, to.getBal());
    }

    @Test
    void wholeCentTransfersConserveMoneyAndAllowSpendingTheFullBalance() {
        Account from = new Account(10.00);
        Account to = new Account(2.00);
        for (int i = 0; i < 1000; i++) {
            MoneyManager.transfer(from, to, 0.01);
        }
        assertEquals(0.00, from.getBal());
        assertEquals(12.00, to.getBal());
    }

    @Test
    void decimalTransfersRejectInvalidAmountsBeforeMutatingAccounts() {
        Account from = new Account(1.00);
        Account to = new Account(2.00);
        assertFalse(MoneyManager.transfer(from, to, new BigDecimal("0.005")));
        assertFalse(MoneyManager.transfer(from, to, new BigDecimal("1.01")));
        assertFalse(MoneyManager.transfer(from, to, (BigDecimal) null));
        assertFalse(MoneyManager.transfer(from, null, new BigDecimal("0.01")));
        assertEquals(1.00, from.getBal());
        assertEquals(2.00, to.getBal());
    }

    @Test
    void decimalTransferReportsSuccessAndPreservesTaxFlags() {
        Account from = new Account(1.00, true);
        Account to = new Account(2.00, false);
        assertTrue(MoneyManager.transfer(from, to, new BigDecimal("1.000")));
        assertEquals(0.00, from.getBal());
        assertEquals(3.00, to.getBal());
        assertTrue(from.isTaxable());
        assertFalse(to.isTaxable());
    }

    @Test
    void transferToSameAccountPreservesBalance() {
        Account account = new Account(1.00);
        assertTrue(MoneyManager.transfer(account, account, new BigDecimal("0.01")));
        assertEquals(1.00, account.getBal());
    }

    @Test
    void legacyFractionalBalancesAreNotRoundedDuringTransfer() {
        Account from = new Account(0);
        Account to = new Account(0);
        from.setBal(1.005);
        to.setBal(0.005);
        assertTrue(MoneyManager.transfer(from, to, new BigDecimal("0.01")));
        assertEquals(0.995, from.getBal());
        assertEquals(0.015, to.getBal());
    }
}
