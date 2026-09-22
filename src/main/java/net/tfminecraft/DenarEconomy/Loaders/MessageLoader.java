package net.tfminecraft.DenarEconomy.Loaders;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import me.Plugins.TLibs.Objects.API.SubAPI.StringFormatter;
import net.tfminecraft.DenarEconomy.DenarEconomy;

public class MessageLoader {
	private static FileConfiguration config = new YamlConfiguration();
	private static String prefix = "";

	public void load(File configFile) {
		FileConfiguration loaded = new YamlConfiguration();
		try {
			loaded.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			DenarEconomy.plugin.getLogger().log(Level.SEVERE, "Could not read messages.yml, falling back to the bundled defaults.", e);
		}

		// Keys added in a later version still resolve on servers whose messages.yml predates them.
		InputStream bundled = DenarEconomy.plugin.getResource("messages.yml");
		if (bundled != null) {
			loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
		}

		config = loaded;
		prefix = config.getString("prefix", "");
	}

	/**
	 * Placeholders are passed as name/value pairs: get("bank.action", "action", "Deposited", "amount", 5.0).
	 */
	public static String get(String path, Object... placeholders) {
		String raw = config.getString(path);
		if (raw == null) {
			DenarEconomy.plugin.getLogger().warning("Missing message: " + path);
			return path;
		}

		raw = raw.replace("%prefix%", prefix);
		for (int i = 0; i + 1 < placeholders.length; i += 2) {
			raw = raw.replace("%" + placeholders[i] + "%", String.valueOf(placeholders[i + 1]));
		}

		return StringFormatter.formatHex(ChatColor.translateAlternateColorCodes('&', raw));
	}

	public static void send(CommandSender to, String path, Object... placeholders) {
		to.sendMessage(get(path, placeholders));
	}
}
