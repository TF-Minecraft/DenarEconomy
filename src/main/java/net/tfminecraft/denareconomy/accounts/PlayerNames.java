package net.tfminecraft.denareconomy.accounts;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Which account a player name belongs to. Online players answer first. Otherwise the server
 * user cache is used, and a name that only resolves to an empty offline-mode id is ignored so
 * money is not written onto an account the player will never see.
 */
public final class PlayerNames {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final DateTimeFormatter EXPIRES = DateTimeFormatter.ofPattern(
			"yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);

	/** One row from the server user cache. */
	public record CachedName(String name, UUID id, long expiresAtMillis) {
	}

	public interface Sources {
		/** Unique id of the player with this name who is online now, or null. */
		UUID online(String name);

		/** In-memory user cache for this name, or null when the server has not seen them. */
		UUID cached(String name);

		List<CachedName> userCache();

		boolean hasAccount(UUID id);
	}

	private final Sources sources;
	private final File indexFile;
	private final Map<String, UUID> remembered = new LinkedHashMap<>();

	public PlayerNames(Sources sources, File indexFile) {
		this.sources = sources;
		this.indexFile = indexFile;
	}

	public void load() {
		if (indexFile == null || !indexFile.isFile()) {
			return;
		}
		try (Reader reader = new FileReader(indexFile)) {
			Map<String, String> raw = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
			if (raw == null) {
				return;
			}
			for (Map.Entry<String, String> entry : raw.entrySet()) {
				if (entry.getKey() == null || entry.getValue() == null) {
					continue;
				}
				try {
					remembered.put(key(entry.getKey()), UUID.fromString(entry.getValue()));
				} catch (IllegalArgumentException ignored) {
					// A damaged row is skipped. The next login writes the name again.
				}
			}
		} catch (IOException ignored) {
			// The index is a convenience. The user cache still resolves names.
		}
	}

	public UUID resolve(String name) {
		if (name == null || name.isBlank() || sources == null) {
			return null;
		}
		UUID online = sources.online(name);
		if (online != null) {
			remember(name, online);
			return online;
		}
		List<CachedName> rows = matches(name, sources.userCache());
		UUID memory = sources.cached(name);
		if (memory != null) {
			rows.add(new CachedName(name, memory, Long.MAX_VALUE));
		}
		UUID chosen = choose(rows, sources::hasAccount);
		if (chosen != null && (sources.hasAccount(chosen) || chosen.version() != 3)) {
			remember(name, chosen);
			return chosen;
		}
		return remembered.get(key(name));
	}

	public void remember(String name, UUID id) {
		if (name == null || name.isBlank() || id == null) {
			return;
		}
		UUID previous = remembered.put(key(name), id);
		if (id.equals(previous)) {
			return;
		}
		save();
	}

	/**
	 * Prefer an id that already has an account, then a Mojang id over an offline-mode id,
	 * then the cache row that expires latest.
	 */
	public static UUID choose(List<CachedName> matches, java.util.function.Predicate<UUID> hasAccount) {
		if (matches == null || matches.isEmpty()) {
			return null;
		}
		List<CachedName> pool = new ArrayList<>();
		if (hasAccount != null) {
			for (CachedName row : matches) {
				if (row != null && row.id() != null && hasAccount.test(row.id())) {
					pool.add(row);
				}
			}
		}
		if (pool.isEmpty()) {
			for (CachedName row : matches) {
				if (row != null && row.id() != null) {
					pool.add(row);
				}
			}
		}
		if (pool.isEmpty()) {
			return null;
		}
		List<CachedName> mojang = new ArrayList<>();
		for (CachedName row : pool) {
			if (row.id().version() != 3) {
				mojang.add(row);
			}
		}
		if (!mojang.isEmpty()) {
			pool = mojang;
		}
		CachedName best = null;
		for (CachedName row : pool) {
			if (best == null || row.expiresAtMillis() >= best.expiresAtMillis()) {
				best = row;
			}
		}
		return best == null ? null : best.id();
	}

	public static List<CachedName> parseUserCache(String json) {
		List<CachedName> rows = new ArrayList<>();
		if (json == null || json.isBlank()) {
			return rows;
		}
		UserCacheRow[] parsed = GSON.fromJson(json, UserCacheRow[].class);
		if (parsed == null) {
			return rows;
		}
		for (UserCacheRow row : parsed) {
			if (row == null || row.name == null || row.uuid == null) {
				continue;
			}
			try {
				rows.add(new CachedName(row.name, UUID.fromString(row.uuid), expires(row.expiresOn)));
			} catch (IllegalArgumentException ignored) {
				// Skip a row whose uuid is not a uuid.
			}
		}
		return rows;
	}

	private List<CachedName> matches(String name, List<CachedName> cache) {
		List<CachedName> rows = new ArrayList<>();
		if (cache == null) {
			return rows;
		}
		String wanted = key(name);
		for (CachedName row : cache) {
			if (row != null && row.name() != null && key(row.name()).equals(wanted)) {
				rows.add(row);
			}
		}
		return rows;
	}

	private void save() {
		if (indexFile == null) {
			return;
		}
		File parent = indexFile.getParentFile();
		if (parent != null && !parent.exists() && !parent.mkdirs()) {
			return;
		}
		Map<String, String> raw = new LinkedHashMap<>();
		for (Map.Entry<String, UUID> entry : remembered.entrySet()) {
			raw.put(entry.getKey(), entry.getValue().toString());
		}
		try (Writer writer = new FileWriter(indexFile)) {
			GSON.toJson(raw, writer);
		} catch (IOException ignored) {
			// The next successful remember tries again.
		}
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	private static long expires(String text) {
		if (text == null || text.isBlank()) {
			return 0L;
		}
		try {
			return OffsetDateTime.parse(text, EXPIRES).toInstant().toEpochMilli();
		} catch (DateTimeParseException ex) {
			return 0L;
		}
	}

	private static final class UserCacheRow {
		String uuid;
		String name;
		String expiresOn;
	}
}
