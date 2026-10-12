package net.tfminecraft.denareconomy.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BankAmountTest {
    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"0.005", "0.001", "0.009", "1.001", "0", "-0.01", "NaN",
        "Infinity", "-Infinity", "1e-400", "1e400", "1.0000000000000001",
        "0.010000000000000001", "0.009999999999999999", "abc", "0x1.0p0", "1e30"})
    void rejectsInvalidInputWithoutFloatingPointRounding(String token) {
        assertEquals(0, CommandManager.parseCents(token));
    }

    @ParameterizedTest
    @CsvSource({"0.01,1", "1,100", "1.2300,123", "1e-2,1", "1000,100000",
        "90071992547409.93,9007199254740993"})
    void preservesValidAmountsExactly(String token, long expected) {
        assertEquals(expected, CommandManager.parseCents(token));
    }
}
