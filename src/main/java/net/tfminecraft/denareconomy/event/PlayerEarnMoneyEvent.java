package net.tfminecraft.denareconomy.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.tfminecraft.tlibs.character.CharacterRef;

/**
 * Fires after a taxable earning has been posted. A retried operation can fire it again. Tax is
 * decided by the registered {@code TaxPolicy} before posting; changing the amount here has no
 * effect on the ledger.
 */
public class PlayerEarnMoneyEvent extends Event{
	private static final HandlerList HANDLERS = new HandlerList();
    private final String p;
    private final CharacterRef character;
    private double amount;
    private boolean taxAssigned;

	public PlayerEarnMoneyEvent(String p, double a, CharacterRef character) {
    	this.p = p;
    	this.amount= a;
    	this.character = character;
    }

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}
	public static HandlerList getHandlerList() {
        return HANDLERS;
    }
	
	/** The earning account's name. */
	public String getPlayer() {
        return this.p;
    }

	/** The character that earned the money. */
	public CharacterRef getCharacter() {
		return character;
	}

	/** Gross amount in denars. */
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
