package net.tfminecraft.denareconomy.database;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.accounts.LegacyCalls;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.enums.Accounts;
import net.tfminecraft.denareconomy.ledger.Ledger;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.Characters;

/**
 * Balances from the ledger. The per-player methods act only on an online player's current
 * character; anyone else is refused and logged. Use {@code net.tfminecraft.denareconomy.api.Denars}.
 */
public class Database {

    /** @deprecated Balances live in the ledger. This does nothing. */
    @Deprecated
    public static void savePlayerData(PlayerData data) {
        LegacyCalls.refused("Database.savePlayerData");
    }

    /** @deprecated Read-only balances of the online player's current character; zero for anyone else. */
    @Deprecated
    public static PlayerData loadPlayerData(UUID player) {
        return LegacyCalls.snapshot(player, "Database.loadPlayerData");
    }

    /** @deprecated The online player's current character's balance; zero for anyone else. */
    @Deprecated
    public static double getPlayerBalance(UUID player, Accounts account) {
        return LegacyCalls.balance(player, account, "Database.getPlayerBalance");
    }

    /** Whether this player is online and playing a character, so has balances to act on. */
    public static boolean hasPlayerData(UUID id) {
        return LegacyCalls.character(id).isPresent();
    }

    /** Every character's and unclaimed legacy balance of this kind, in denars. */
    public static double getTotalAmount(Accounts account) {
        String label = account.name().toLowerCase(Locale.ROOT);
        long total = 0;
        for (String kind : List.of("char", "legacy")) {
            for (Ledger.Row row : DenarEconomy.getLedger().accounts(kind)) {
                if (row.label().equals(label)) {
                    total += row.cents();
                }
            }
        }
        return total / 100.0;
    }

    /** The richest characters by pouch and bank together. */
    public static List<BalTopEntry> getTopBalances(int limit) {
        Map<String, Long> totals = new HashMap<>();
        for (Ledger.Row row : DenarEconomy.getLedger().accounts("char")) {
            totals.merge(row.character(), row.cents(), Long::sum);
        }
        return totals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> new BalTopEntry(name(entry.getKey()), entry.getValue() / 100.0))
                .toList();
    }

    private static String name(String characterId) {
        return Characters.info(UUID.fromString(characterId)).map(CharacterInfo::name)
                .orElse(characterId.substring(0, 8));
    }
}
