package net.tfminecraft.denareconomy.database;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.enums.Accounts;

public class Database {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final File dataDir = new File("plugins/DenarEconomy/PlayerData");

    /** Returns only after a complete replacement; failures leave the previous file intact. */
    public static void savePlayerData(PlayerData data) {
        Path file = dataDir.toPath().resolve(data.getId().toString() + ".json");
        String json = gson.toJson(data);
        try {
            Files.createDirectories(dataDir.toPath());
            Path temporary = Files.createTempFile(dataDir.toPath(), data.getId() + "-", ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    ByteBuffer bytes = StandardCharsets.UTF_8.encode(json);
                    while (bytes.hasRemaining()) channel.write(bytes);
                    channel.force(true);
                }
                // Fail closed if atomic replacement is unavailable on this filesystem.
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save account " + data.getId(), e);
        }
        // Replacement has committed. A directory-sync failure must not roll back only memory.
        try (FileChannel directory = FileChannel.open(dataDir.toPath(), StandardOpenOption.READ)) {
            directory.force(true);
        } catch (IOException e) {
            Bukkit.getLogger().log(Level.WARNING, "Saved account " + data.getId()
                    + " but could not sync its directory; power-loss durability is not guaranteed", e);
        }
    }

    public static PlayerData loadPlayerData(UUID player) {
        File file = new File(dataDir, player.toString() + ".json");
        if (!hasPlayerData(player)) {
            return new PlayerData(player);
        }

        try (Reader reader = new FileReader(file)) {
            PlayerData data = gson.fromJson(reader, PlayerData.class);
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load account " + player, e);
        }
    }

    public static double getPlayerBalance(UUID player, Accounts account) {
        PlayerData data = loadPlayerData(player);
        return switch (account) {
            case POUCH -> data.getPouch().getBal();
            case BANK -> data.getBank().getBal();
        };
    }

    public static boolean hasPlayerData(UUID id) {
        File file = new File(dataDir, id.toString() + ".json");
        try {
            Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not inspect account " + id, e);
        }
    }

    public static double getTotalAmount(Accounts account) {
        if (!dataDir.exists() || !dataDir.isDirectory()) return 0.0;

        double total = 0.0;

        File[] files = dataDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return 0.0;

        for (File file : files) {
            try (Reader reader = new FileReader(file)) {
                PlayerData data = gson.fromJson(reader, PlayerData.class);
                total += switch (account) {
                    case POUCH -> data.getPouch().getBal();
                    case BANK -> data.getBank().getBal();
                };
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
