package net.tfminecraft.denareconomy.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BankAmountTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0.005", "0.001", "0.009", "1.001", "0", "-0.01", "NaN",
        "Infinity", "-Infinity", "1e-400", "1e400", "1.0000000000000001",
        "0.010000000000000001", "0.009999999999999999", "abc", "0x1.0p0"})
    void rejectsInvalidInputWithoutFloatingPointRounding(String token) {
        assertNull(CommandManager.parseBankAmount(token));
    }

    @ParameterizedTest
    @CsvSource({"0.01,0.01", "1,1.00", "1.2300,1.23", "1e-2,0.01", "1000,1000.00",
        "90071992547409.93,90071992547409.93"})
    void preservesValidAmountsExactly(String token, String expected) {
        assertEquals(new BigDecimal(expected), CommandManager.parseBankAmount(token));
    }
}
