package net.tfminecraft.denareconomy.database;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.tfminecraft.denareconomy.DenarEconomy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.enums.Accounts;
import net.tfminecraft.denareconomy.ledger.Ledger;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.Characters;

/** Ledger totals, rankings and online character readiness. */
public class Database {

    /** Whether this player is online and playing a character, so has balances to act on. */
    public static boolean hasPlayerData(UUID id) {
        Player player = id == null ? null : Bukkit.getPlayer(id);
        return player != null && Denars.active(player).isPresent();
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
