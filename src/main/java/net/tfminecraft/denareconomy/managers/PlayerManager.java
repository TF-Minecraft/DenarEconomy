package net.tfminecraft.denareconomy.managers;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import net.tfminecraft.denareconomy.accounts.LegacyCalls;
import net.tfminecraft.denareconomy.accounts.OfflineModifier;
import net.tfminecraft.denareconomy.data.PlayerData;

public class PlayerManager implements Listener{

	/**
	 * @deprecated Read-only balances of the player's current character, zero without one. Use
	 *             {@code Denars}.
	 */
	@Deprecated
	public PlayerData get(Player p) {
		return get(p.getUniqueId());
	}

	/**
	 * @deprecated Read-only balances of the online player's current character, zero for anyone
	 *             else. Use {@code Denars}.
	 */
	@Deprecated
	public PlayerData get(UUID id) {
		return LegacyCalls.snapshot(id, "PlayerManager.get");
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		Player p = e.getPlayer();
		OfflineModifier.remember(p.getName(), p.getUniqueId());
	}
}
