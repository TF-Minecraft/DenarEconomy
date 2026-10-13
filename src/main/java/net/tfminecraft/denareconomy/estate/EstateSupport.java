package net.tfminecraft.denareconomy.estate;

import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;

import net.tfminecraft.denareconomy.ledger.MoneyEstates;

/**
 * Starts the money estate participant when TLibs has the estate API (2.6.0 and later). Nothing
 * here names an estate class, so DenarEconomy still enables on an older TLibs and simply takes no
 * part in estates.
 */
public final class EstateSupport {
	static final String[] CLASSES = {
		"net.tfminecraft.tlibs.character.estate.Estates",
		"net.tfminecraft.tlibs.character.estate.EstateParticipant",
		"net.tfminecraft.tlibs.character.estate.EstateDecision",
		"net.tfminecraft.tlibs.character.estate.Outcome",
		"net.tfminecraft.tlibs.character.estate.GuildLookup",
		"net.tfminecraft.tlibs.character.estate.Holdings"
	};

	private EstateSupport() {
	}

	/** Whether every named class loads. */
	static boolean present(String... classNames) {
		try {
			for (String name : classNames) {
				Class.forName(name, false, EstateSupport.class.getClassLoader());
			}
			return true;
		} catch (ReflectiveOperationException | LinkageError e) {
			return false;
		}
	}

	/** Registers the participant and its hourly sweep, once the ledger is open. */
	public static void start(Plugin plugin) {
		start(plugin, CLASSES);
	}

	static void start(Plugin plugin, String... classNames) {
		Logger log = plugin.getLogger();
		if (!present(classNames)) {
			log.info("TLibs has no estate API; dead characters' money stays where it is");
			return;
		}
		MoneyEstates.register(plugin);
	}
}
