package net.tfminecraft.DenarEconomy.Managers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PouchDeathPolicyTest {

	@Test
	void dropsOnNormalPvpDeath() {
		assertTrue(PouchDeathPolicy.shouldDropPouch(false, false));
	}

	@Test
	void skipsWhenKeepInventory() {
		assertFalse(PouchDeathPolicy.shouldDropPouch(true, false));
	}

	@Test
	void skipsWhenKeepPouchMetadata() {
		assertFalse(PouchDeathPolicy.shouldDropPouch(false, true));
	}

	@Test
	void skipsWhenBothKeepInventoryAndMetadata() {
		assertFalse(PouchDeathPolicy.shouldDropPouch(true, true));
	}
}
