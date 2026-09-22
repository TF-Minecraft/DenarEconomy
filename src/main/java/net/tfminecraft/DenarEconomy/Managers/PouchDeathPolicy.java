package net.tfminecraft.DenarEconomy.Managers;

/**
 * Pouch coin drops on PvP death. Keep-inventory or battle metadata skips the drop.
 * Metadata key must match SimpleFactions {@code BattleManager.KEEP_POUCH_METADATA}.
 */
public final class PouchDeathPolicy {

	public static final String KEEP_POUCH_METADATA = "simplefactions.keep-pouch";

	private PouchDeathPolicy() {
	}

	public static boolean shouldDropPouch(boolean keepInventory, boolean keepPouchMetadata) {
		if (keepInventory) {
			return false;
		}
		return !keepPouchMetadata;
	}
}
