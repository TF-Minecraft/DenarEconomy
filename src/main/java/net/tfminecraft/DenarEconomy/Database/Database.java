package net.tfminecraft.DenarEconomy.Database;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.tfminecraft.DenarEconomy.Data.PlayerData;
import net.tfminecraft.DenarEconomy.Enum.Accounts;

public class Database {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final File dataDir = new File("plugins/DenarEconomy/PlayerData");

    public static void savePlayerData(PlayerData data) {
        if (!dataDir.exists()) dataDir.mkdirs();

        File file = new File(dataDir, data.getId().toString() + ".json");
        try (Writer writer = new FileWriter(file)) {
            gson.toJson(data, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static PlayerData loadPlayerData(UUID player) {
        File file = new File(dataDir, player.toString() + ".json");
        if (!file.exists()) {
            return new PlayerData(player);
        }

        try (Reader reader = new FileReader(file)) {
            PlayerData data = gson.fromJson(reader, PlayerData.class);
            return data;
        } catch (IOException e) {
            e.printStackTrace();
            return new PlayerData(player); // Fallback
        }
    }

    public static double getPlayerBalance(UUID player, Accounts account) {
        PlayerData data = loadPlayerData(player);
        switch (account) {
            case POUCH:
                return data.getPouch().getBal();
            case BANK:
                return data.getBank().getBal();
            default:
                return 0.0;
        }
    }

    public static boolean hasPlayerData(UUID id) {
        File file = new File(dataDir, id.toString() + ".json");
        return file.exists();
    }

    public static double getTotalAmount(Accounts account) {
        if (!dataDir.exists() || !dataDir.isDirectory()) return 0.0;

        double total = 0.0;

        File[] files = dataDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return 0.0;

        for (File file : files) {
            try (Reader reader = new FileReader(file)) {
                PlayerData data = gson.fromJson(reader, PlayerData.class);
                switch (account) {
                    case POUCH:
                        total += data.getPouch().getBal();
                        break;
                    case BANK:
                        total += data.getBank().getBal();
                        break;
                }
            } catch (IOException | NullPointerException e) {
                e.printStackTrace(); // Optional: log which file caused issues
            }
        }

        return total;
    }

    public static List<BalTopEntry> getTopBalances(int limit) {
        List<BalTopEntry> list = new ArrayList<>();

        if (!dataDir.exists() || !dataDir.isDirectory()) return list;

        File[] files = dataDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return list;

        for (File file : files) {
            try (Reader reader = new FileReader(file)) {
                PlayerData data = gson.fromJson(reader, PlayerData.class);
                double total = data.getPouch().getBal() + data.getBank().getBal();

                // Try to get the player name (from file or UUID)
                String name = Bukkit.getOfflinePlayer(data.getId()).getName();
                if (name == null) name = data.getId().toString().substring(0, 8); // fallback

                list.add(new BalTopEntry(name, total));
            } catch (IOException | NullPointerException e) {
                e.printStackTrace();
            }
        }

        // Sort descending
        list.sort((a, b) -> Double.compare(b.getTotal(), a.getTotal()));

        // Return top N
        return list.size() > limit ? list.subList(0, limit) : list;
    }
}
