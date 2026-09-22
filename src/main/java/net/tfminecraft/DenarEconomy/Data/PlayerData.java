package net.tfminecraft.DenarEconomy.Data;

import java.util.UUID;

public class PlayerData {
	private UUID id;
	private Account pouch;
	private Account bank;
	
	public PlayerData(UUID id) {
		this.id = id;
		this.pouch = new Account(0);
		this.bank = new Account(0, false);
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
