package net.tfminecraft.denareconomy.item;

import org.bukkit.configuration.ConfigurationSection;

public class Coin {
	String id;
	String item;
	Double value;
	boolean withdraw;
	public String getId() {
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}
	
	public String getItem() {
		return item;
	}
	public void setItem(String item) {
		this.item = item;
	}
	public Double getValue() {
		return value;
	}
	public void setValue(Double value) {
		this.value = value;
	}
	public boolean canWithdraw() {
		return this.withdraw;
	}
	public Coin(String s, ConfigurationSection config) {
		this.id = s;
		this.item = config.getString("item");
		this.value = config.getDouble("value");
		if(config.contains("withdraw")) {
			this.withdraw = config.getBoolean("withdraw");
		} else {
			withdraw = true;
		}
	}
}
