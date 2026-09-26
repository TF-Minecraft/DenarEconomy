package net.tfminecraft.denareconomy.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class PlayerEarnMoneyEvent extends Event{
	private static final HandlerList HANDLERS = new HandlerList();
    private final String p;
    private double amount;
    private boolean taxAssigned;

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
    /** Tax explicitly assigned by a listener, or zero when no listener sets it. */
    public double getTax() {
        return taxAssigned ? amount : 0.0;
    }

    /** Retains the legacy API: listeners read the gross amount, then replace it with tax. */
    public void setAmount(double a) {
		this.amount = a;
		this.taxAssigned = true;
	}
}
