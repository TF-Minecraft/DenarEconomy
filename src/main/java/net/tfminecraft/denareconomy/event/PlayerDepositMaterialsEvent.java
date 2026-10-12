package net.tfminecraft.denareconomy.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.character.CharacterRef;

public class PlayerDepositMaterialsEvent extends Event implements Cancellable{
	private static final HandlerList HANDLERS = new HandlerList();
    private boolean isCancelled;
    private final Player p;
    private final CharacterRef character;
    private final ItemStack item;
	@Override
	public boolean isCancelled() {
		return this.isCancelled;
	}

	@Override
	public void setCancelled(boolean arg0) {
		this.isCancelled = arg0;
		
	}
	public PlayerDepositMaterialsEvent(Player p, ItemStack i, CharacterRef character) {
    	this.p = p;
    	this.character = character;
    	this.item= i;
    }

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}
	public static HandlerList getHandlerList() {
        return HANDLERS;
    }
	
	public Player getPlayer() {
        return this.p;
    }
    public ItemStack getItem() {
    	return this.item;
    }

	/** The character the player is playing. */
	public CharacterRef getCharacter() {
		return character;
	}
}
