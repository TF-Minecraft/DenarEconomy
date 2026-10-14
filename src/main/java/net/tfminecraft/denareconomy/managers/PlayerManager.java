package net.tfminecraft.denareconomy.managers;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import net.tfminecraft.denareconomy.accounts.OfflineModifier;

public class PlayerManager implements Listener{

	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		Player p = e.getPlayer();
		OfflineModifier.remember(p.getName(), p.getUniqueId());
	}
}
