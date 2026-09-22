package net.tfminecraft.DenarEconomy.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class PlayerEarnMoneyEvent extends Event{
	private static final HandlerList HANDLERS = new HandlerList();
    private final String p;
    private double amount;

	public PlayerEarnMoneyEvent(String p, double a) {
    	this.p = p;
    	this.amount= a;
    }

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}
	public static HandlerList getHandlerList() {
        return HANDLERS;
    }
	
	public String getPlayer() {
        return this.p;
    }
    public double getAmount() {
    	return this.amount;
    }
    public void setAmount(double a) {
		this.amount = a;
	}
}
