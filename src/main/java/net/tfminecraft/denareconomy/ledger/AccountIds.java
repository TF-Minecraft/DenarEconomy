package net.tfminecraft.denareconomy.ledger;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;

/** Ledger account IDs and the rules that follow from an account's kind. */
public final class AccountIds {
	/** Source and sink for coins entering or leaving as items, admin credits and imports. */
	public static final String MINT = "server:mint";
	/** Holds tax that has no jurisdiction. */
	public static final String TAX_VOID = "server:tax-void";

	private static final String ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final Pattern FORMAT = Pattern.compile(
			"(char|legacy):(" + ID + "):(pouch|bank)|(guild|guildtax):(" + ID + ")|server:(mint|tax-void)");

	/**
	 * A parsed account ID. {@code owner} is the legacy account or guild incarnation and
	 * {@code character} the character; {@code label} is pouch, bank, mint or tax-void.
	 */
	public record Parts(String kind, String owner, String character, String label) {
	}

	private AccountIds() {
	}

	public static String pouch(UUID characterId) {
		return "char:" + characterId + ":pouch";
	}

	public static String bank(UUID characterId) {
		return "char:" + characterId + ":bank";
	}

	public static String guild(UUID incarnation) {
		return "guild:" + incarnation;
	}

	public static String guildTax(UUID incarnation) {
		return "guildtax:" + incarnation;
	}

	public static String legacyPouch(UUID accountId) {
		return "legacy:" + accountId + ":pouch";
	}

	public static String legacyBank(UUID accountId) {
		return "legacy:" + accountId + ":bank";
	}

	/** @throws IllegalArgumentException if this is not a ledger account ID */
	public static Parts parse(String id) {
		Matcher match = FORMAT.matcher(String.valueOf(id));
		if (!match.matches()) {
			throw new IllegalArgumentException("Not a ledger account: " + id);
		}
		if (match.group(1) != null) {
			boolean character = match.group(1).equals("char");
			return new Parts(match.group(1), character ? null : match.group(2),
					character ? match.group(2) : null, match.group(3));
		}
		if (match.group(4) != null) {
			return new Parts(match.group(4), match.group(5), null, null);
		}
		return new Parts("server", null, null, match.group(6));
	}

	/** System accounts and guild treasuries may go below zero; every other account may not. */
	public static boolean mayGoNegative(String id) {
		return id.startsWith("server:") || id.startsWith("guild:");
	}

	/** Whether this is a dead character's account, which refuses debits outside staff and estates. */
	public static boolean deadCharacter(String id) {
		String character = parse(id).character();
		return character != null && Characters.info(UUID.fromString(character))
				.map(info -> info.status() == CharacterStatus.DEAD).orElse(false);
	}
}
