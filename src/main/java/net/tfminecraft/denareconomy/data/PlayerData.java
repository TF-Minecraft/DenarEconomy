package net.tfminecraft.denareconomy.data;

import java.util.UUID;

/** A player's pouch and bank. Also the shape of the pre-ledger {@code PlayerData/<uuid>.json} files. */
public class PlayerData {
	private UUID id;
	private Account pouch;
	private Account bank;

	private PlayerData(UUID id, Account pouch, Account bank) {
		this.id = id;
		this.pouch = pouch;
		this.bank = bank;
	}

	/** Read-only balances, in cents. */
	public static PlayerData snapshot(UUID id, long pouchCents, long bankCents) {
		return new PlayerData(id, Account.snapshot(pouchCents, true), Account.snapshot(bankCents, false));
	}

	public UUID getId(){
		return id;
	}

	public Account getPouch() {
		return pouch;
	}
	
	public Account getBank() {
		return bank;
	}
}
