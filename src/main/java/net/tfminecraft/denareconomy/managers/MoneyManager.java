package net.tfminecraft.denareconomy.managers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.enums.APIType;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.Indyuce.mmoitems.MMOItems;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.data.Account;
import net.tfminecraft.denareconomy.data.PlayerData;
import net.tfminecraft.denareconomy.database.Database;
import net.tfminecraft.denareconomy.drop.Drop;
import net.tfminecraft.denareconomy.enums.Accounts;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.loaders.CoinLoader;
import net.tfminecraft.denareconomy.loaders.DropLoader;
import net.tfminecraft.denareconomy.loaders.MessageLoader;
import net.tfminecraft.denareconomy.event.PlayerDepositMaterialsEvent;
import net.tfminecraft.denareconomy.event.PlayerBankPulseEvent;
import net.tfminecraft.denareconomy.event.PlayerEarnMoneyEvent;

public class MoneyManager implements Listener{
	private PlayerManager pm = DenarEconomy.getPlayerManager();

	private final Map<UUID, ArmorStand> standMap = new HashMap<>();
	private final Map<UUID, Integer> taskMap = new HashMap<>();
	private final Map<UUID, Long> pouchCooldown = new HashMap<>();

	private static final long POUCH_COOLDOWN_MILLIS = 5000L;
	private static final long POUCH_DISPLAY_TICKS = 20L * 5;

	public Map<UUID, ArmorStand> getStandMap() {
		return standMap;
	}

	public Map<UUID, Integer> getTaskMap() {
		return taskMap;
	}

	
	public Coin getCoin(ItemStack i) {
		ItemAPI api = (ItemAPI) TLibs.getApiInstance(APIType.ITEM_API);
		for(Coin c : CoinLoader.get()) {
			if(api.getChecker().checkItemWithPath(i, c.getItem())) return c;
		}
		return null;
	}
	
	public double combinedValue(Coin c, ItemStack i) {
		double v = c.getValue()*100.0*i.getAmount();
		return Math.round(v)/100.0;
	}
	
	public static void transfer(Account from, Account to, double amount) {
		from.change(amount*-1);
		to.change(amount);
	}

	public void showPouch(Player p) {
		UUID uuid = p.getUniqueId();

		Long ready = pouchCooldown.get(uuid);
		long now = System.currentTimeMillis();
		if (ready != null && now < ready) {
			MessageLoader.send(p, "errors.pouch-cooldown", "seconds", String.format("%.1f", (ready - now) / 1000.0));
			return;
		}
		pouchCooldown.put(uuid, now + POUCH_COOLDOWN_MILLIS);

		// Remove old stand if exists
		if (standMap.containsKey(uuid)) {
			ArmorStand old = standMap.remove(uuid);
			if (old != null && !old.isDead()) old.remove();

			Integer oldTask = taskMap.remove(uuid);
			if (oldTask != null) Bukkit.getScheduler().cancelTask(oldTask);
		}

		double a = pm.get(p).getPouch().getBal();
		double inv = 0;

		for (ItemStack i : p.getInventory().getContents()) {
			if (i == null || i.getType() == Material.AIR) continue;
			Coin c = getCoin(i);
			if (c == null || !c.canWithdraw()) continue;
			inv += combinedValue(c, i);
		}

		a += inv;
		final double amount = a;

		MessageLoader.send(p, "pouch.showing", "amount", amount);

		ArmorStand stand = p.getWorld().spawn(p.getLocation().add(0, 2.2, 0), ArmorStand.class, as -> {
			as.setCustomName(MessageLoader.get("pouch.hologram", "amount", String.format("%.2f", amount)));
			as.setCustomNameVisible(true);
			as.setVisible(false);
			as.setMarker(true);
			as.setGravity(false);
			as.setSmall(true);
			as.setPersistent(false); // never written to the world save, so a crash cannot orphan it
		});

		standMap.put(uuid, stand);

		int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(DenarEconomy.plugin, () -> {
			if (!p.isOnline() || stand.isDead()) return;
			stand.teleport(p.getLocation().add(0, 2.2, 0));
		}, 0L, 2L);

		taskMap.put(uuid, taskId);

		// Cleanup after 5 seconds
		Bukkit.getScheduler().runTaskLater(DenarEconomy.plugin, () -> {
			// only clear the entry if it is still the stand this call spawned
			if (standMap.get(uuid) == stand) {
				standMap.remove(uuid);

				Integer t = taskMap.remove(uuid);
				if (t != null) Bukkit.getScheduler().cancelTask(t);
			}
			if (!stand.isDead()) stand.remove();
		}, POUCH_DISPLAY_TICKS);
	}
	
	@EventHandler
	public void onQuitClearStand(PlayerQuitEvent e) {
		UUID uuid = e.getPlayer().getUniqueId();

		ArmorStand stand = standMap.remove(uuid);
		if (stand != null && !stand.isDead()) stand.remove();

		Integer taskId = taskMap.remove(uuid);
		if (taskId != null) Bukkit.getScheduler().cancelTask(taskId);

		pouchCooldown.remove(uuid);
	}

	@EventHandler
	public void onChunkUnload(ChunkUnloadEvent e) {
		Iterator<Map.Entry<UUID, ArmorStand>> iterator = standMap.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, ArmorStand> entry = iterator.next();
			ArmorStand stand = entry.getValue();
			if (stand != null && stand.getLocation().getChunk().equals(e.getChunk())) {
				stand.remove(); // remove entity from world
				iterator.remove(); // remove entry from map
				Integer taskId = taskMap.remove(entry.getKey());
				if (taskId != null) Bukkit.getScheduler().cancelTask(taskId);
			}
		}
	}




	
	public double doTaxes(String p, double amount) {
		PlayerEarnMoneyEvent event = new PlayerEarnMoneyEvent(p, amount);
		Bukkit.getPluginManager().callEvent(event);
		return Math.round(event.getAmount()*100.0)/100.0;
	}
	
	public void addMoney(Player p, double amount, boolean silent, boolean taxable) {
		addMoneyToAccount(p.getUniqueId().toString(), amount, silent, taxable, Accounts.POUCH);
	}

	public double getServerBal(Accounts account) {
		return Database.getTotalAmount(account);
	}

	public double getBalance(Accounts account, UUID player) {
		PlayerData pd = pm.get(player);
		if(pd == null) return Database.getPlayerBalance(player, account);
		Account acc = null;
		switch (account) {
			case POUCH:
				acc = pd.getPouch();
				break;
			case BANK:
				acc = pd.getBank();
				break;
			default:
				break;
		}
		if(acc == null) return 0.0;
		return acc.getBal();
	}

	public void changeBal(String id, double amount, Accounts a){
		PlayerData pd = pm.get(UUID.fromString(id));
		Account account = null;
		switch (a) {
			case POUCH:
				account = pd.getPouch();
				break;
			case BANK:
				account = pd.getBank();
				break;
			default:
				break;
		}
		if(account == null) return;
		account.change(amount);
		Player p = Bukkit.getPlayer(UUID.fromString(id));
		if(p == null) {
			pm.save(UUID.fromString(id));
		}
	}

	public void addMoneyToAccount(String id, double amount, boolean silent, boolean taxable, Accounts a) {
		if(id == null){
			System.out.println("Error null ID");
			return;
		}
		Player p = Bukkit.getPlayer(UUID.fromString(id));
		String name = " ";
		if(p != null && p.isOnline()){
			name = p.getName();
		} else {
			name = Bukkit.getOfflinePlayer(UUID.fromString(id)).getName();
		}
		double tax = 0.0;
		if(taxable) {
			tax = doTaxes(name, amount);
			if(tax > 0) {
				amount -= tax;
			}
			amount = Math.round(amount*100.0)/100.0;
		}
		
		if(!silent && p != null) {
			MessageLoader.send(p, "money.earned", "amount", amount);
			if(tax > 0) MessageLoader.send(p, "money.tax", "tax", tax);
		}
		
		if(amount <= 0) return;
		changeBal(id, amount, a);
	}
	
	public List<ItemStack> amountToItems(double amount) {
		return amountToItems(amount, 0.0, 0.0);
	}

	/**
	 * Coins for this amount, using nothing bigger than maxUnitValue. Pass 0 for no cap.
	 * Used to make change: a 100 pouch is worth nothing to someone who has to pay 50.
	 */
	public List<ItemStack> amountToItems(double amount, double maxUnitValue) {
		return amountToItems(amount, maxUnitValue, 0.0);
	}

	/**
	 * Coins for this amount, using nothing bigger than maxUnitValue and nothing smaller than
	 * minUnitValue. Pass 0 for either to leave it uncapped. When the smallest allowed coin cannot
	 * finish the amount exactly, this returns an empty list rather than short change.
	 */
	public List<ItemStack> amountToItems(double amount, double maxUnitValue, double minUnitValue) {
	    Map<String, Long> itemCounts = CoinChange.plan(denominations(),
	            Math.round(amount * 100.0),
	            maxUnitValue > 0 ? Math.round(maxUnitValue * 100.0) : CoinChange.NO_LIMIT,
	            minUnitValue > 0 ? Math.round(minUnitValue * 100.0) : CoinChange.NO_LIMIT);
	    if (itemCounts == null) return new ArrayList<>();
	    return items(itemCounts);
	}

	/** Count stacks of one coin, same construction as amountToItems. */
	public List<ItemStack> coinItems(Coin coin, long count) {
		if (coin == null || coin.getItem() == null || count < 1) {
			return new ArrayList<>();
		}
		return items(Map.of(coin.getItem(), count));
	}

	/** Turn a plan of item id to count into real stacks. */
	private List<ItemStack> items(Map<String, Long> itemCounts) {
	    List<ItemStack> items = new ArrayList<>();
	    for (Map.Entry<String, Long> entry : itemCounts.entrySet()) {
	        String[] parts = entry.getKey().split("\\.");
	        int count = (int) Math.min(entry.getValue(), Integer.MAX_VALUE);

	        if (parts[0].equalsIgnoreCase("v")) {
	            ItemStack item = new ItemStack(Material.valueOf(parts[1].toUpperCase()), count);
	            items.add(item);
	        } else if (parts[0].equalsIgnoreCase("m")) {
	            // MMOItems usually returns a new ItemStack each time, so we set the amount after fetching
	            ItemStack item = MMOItems.plugin.getItem(parts[1].toUpperCase(), parts[2].toUpperCase());
	            if (item != null) {
	                item.setAmount(count);
	                items.add(item);
	            }
	        }
	    }

	    return items;
	}

	
	/**
	 * Smaller coins worth the same as one of these, or an empty list when that cannot be done.
	 *
	 * <p>For making change: someone holding a single 100 pouch cannot pay 50 with it, but they can
	 * pay with what it breaks into. The result is strictly smaller denominations that add up to the
	 * same value, so nothing is created or destroyed. Coins marked withdraw: false are never
	 * produced, and minUnitValue stops a break going below a size that is any use.
	 */
	public List<ItemStack> breakCoin(ItemStack stack, double minUnitValue) {
		if (stack == null || stack.getAmount() < 1) return new ArrayList<>();
		Coin coin = getCoin(stack);
		if (coin == null || coin.getValue() == null) return new ArrayList<>();

		long want = Math.round(coin.getValue() * 100.0);
		long min = minUnitValue > 0 ? Math.round(minUnitValue * 100.0) : CoinChange.NO_LIMIT;
		List<CoinChange.Denom> denominations = denominations();
		Map<String, Long> counts = CoinChange.breakInto(denominations, want, min);
		if (counts == null) return new ArrayList<>();
		// Belt and braces: change that does not add up is worse than no change at all.
		if (CoinChange.value(denominations, counts) != want) return new ArrayList<>();

		return items(counts);
	}

	/** The configured denominations in descending value order, in cents. */
	private List<CoinChange.Denom> denominations() {
		List<CoinChange.Denom> out = new ArrayList<>();
		for (Coin c : CoinLoader.getSortedCoins()) {
			if (c.getValue() == null) continue;
			out.add(new CoinChange.Denom(c.getItem(), Math.round(c.getValue() * 100.0), c.canWithdraw()));
		}
		return out;
	}

	public Item spawnMoney(Player p, Location loc, ItemStack i, boolean silent) {
		Location spawnLoc = loc;
		if(p != null) spawnLoc = p.getEyeLocation();
		if(silent) {
			ItemMeta m = i.getItemMeta();
			NamespacedKey key = new NamespacedKey(DenarEconomy.plugin, "silent");
			m.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, 1);
			i.setItemMeta(m);
		}
		Item item = spawnLoc.getWorld().dropItem(spawnLoc.clone().add(0, -0.4, 0), i);
		if(p != null) {
			p.swingMainHand();
			item.setVelocity(p.getLocation().getDirection().clone().normalize().multiply(0.5));
		}
		return item;
	}

	@SuppressWarnings("unused")
	public void dropItems(Player p, Location loc, double amount) {
		final Vector launchVector = new Vector(
			(Math.random() - 0.5) * 0.2, // small horizontal motion (left/right)
			0.2 + Math.random() * 0.1,   // slight upward motion
			(Math.random() - 0.5) * 0.2  // small horizontal motion (forward/back)
		);
		List<ItemStack> items = amountToItems(amount);
	    if (items.isEmpty()) return;

	    boolean[] named = {false}; // Use array to allow modification in inner class
	    final UUID[] idHolder = new UUID[1];
	    final ItemStack[] firstItemHolder = new ItemStack[1];

	    for (int i = 0; i < items.size(); i++) {
	        final ItemStack original = items.get(i);
	        final int index = i;

	        new BukkitRunnable() {
	            @Override
	            public void run() {
	                ItemStack item = original.clone(); // Clone to avoid shared meta issues
	                ItemMeta m = item.getItemMeta();
					if(p != null) {
						NamespacedKey senderKey = new NamespacedKey(DenarEconomy.plugin, "sender");
	                	m.getPersistentDataContainer().set(senderKey, PersistentDataType.STRING, p.getUniqueId().toString());
					}
	                

	                NamespacedKey valueKey = new NamespacedKey(DenarEconomy.plugin, "customValue");

	                if (!named[0]) {
	                    named[0] = true;
	                    m.getPersistentDataContainer().set(valueKey, PersistentDataType.DOUBLE, amount);
	                    item.setItemMeta(m);
	                    Item first = spawnMoney(p, loc, item, false);
						if(p == null) first.setVelocity(launchVector);
	                    idHolder[0] = first.getUniqueId();
	                    firstItemHolder[0] = first.getItemStack();
	                } else {
	                    if (idHolder[0] == null) {
	                        if(p != null) MessageLoader.send(p, "errors.item-chain-broken");
	                        return;
	                    }

	                    m.getPersistentDataContainer().set(valueKey, PersistentDataType.DOUBLE, 0.0);
	                    NamespacedKey chain = new NamespacedKey(DenarEconomy.plugin, "chained");
	                    m.getPersistentDataContainer().set(chain, PersistentDataType.STRING, idHolder[0].toString());
	                    item.setItemMeta(m);

	                    Item newItem = spawnMoney(p, loc, item, true);
						if(p == null) newItem.setVelocity(launchVector);

	                    if (firstItemHolder[0] != null) {
	                        ItemMeta firstM = firstItemHolder[0].getItemMeta();
	                        NamespacedKey chainTo = new NamespacedKey(DenarEconomy.plugin, "chained_to");
	                        String current = firstM.getPersistentDataContainer().get(chainTo, PersistentDataType.STRING);
	                        if (current == null) {
	                            firstM.getPersistentDataContainer().set(chainTo, PersistentDataType.STRING, newItem.getUniqueId().toString());
	                        } else {
	                            firstM.getPersistentDataContainer().set(chainTo, PersistentDataType.STRING, current + ";" + newItem.getUniqueId().toString());
	                        }
	                        firstItemHolder[0].setItemMeta(firstM);
	                    }
	                }
	            }
	        }.runTaskLater(DenarEconomy.plugin, index);
	    }
	}
	
	public void pay(Player p, double amount) {
	    PlayerData pd = pm.get(p);
	    Account pouch = pd.getPouch();

	    if (pouch.getBal() < amount) {
	        MessageLoader.send(p, "errors.not-enough-pouch");
	        return;
	    }

	    pouch.change(-amount);
	    dropItems(p, null, amount);
	}

	@EventHandler
	public void breakBlock(BlockBreakEvent e) {
		if(e.getPlayer() == null) return;
		Block b = e.getBlock();
		Drop drop = DropLoader.getByBlock(b.getType());
		if(drop == null) return;

		// Check if it's a crop and fully grown
		BlockData data = b.getBlockData();
		if (data instanceof Ageable ageable) {
			if (ageable.getAge() < ageable.getMaximumAge()) {
				return; // Not fully grown
			}
		}

		ItemStack tool = e.getPlayer().getInventory().getItemInMainHand();
		double chance = drop.getChance();
		if (tool != null && tool.containsEnchantment(Enchantment.FORTUNE)) {
			int fortuneLevel = tool.getEnchantmentLevel(Enchantment.FORTUNE);
			double rolls = 1 + fortuneLevel * DropLoader.fortuneStrength;
			chance = 1 - Math.pow(1 - chance, rolls);
		}
		final double rollChance = chance;

		new BukkitRunnable() {
			@Override
			public void run() {
				if(b.getLocation().getBlock().getType().equals(drop.getBlock())) return;
				if(Math.random() < rollChance) {
					dropItems(null, b.getLocation().clone().add(0.5, 0.1, 0.5), drop.getAmount());
				}
			}
		}.runTaskLater(DenarEconomy.plugin, 5);
	}

	@EventHandler
	public void depositMaterials(PlayerInteractEvent e) {
		if(!(e.getAction().equals(Action.RIGHT_CLICK_AIR) || e.getAction().equals(Action.RIGHT_CLICK_BLOCK))) return;
		if(e.getHand() != EquipmentSlot.HAND) return;
		Player p = e.getPlayer();
		ItemStack i = p.getInventory().getItemInMainHand();
		if(i == null || i.getType().equals(Material.AIR)) return;
		PlayerDepositMaterialsEvent event = new PlayerDepositMaterialsEvent(p, i);
		Bukkit.getPluginManager().callEvent(event);
	}
	
	@EventHandler
	public void pickupCoin(EntityPickupItemEvent e) {
		if(!(e.getEntity() instanceof Player)) return;
		Player p = (Player) e.getEntity();
		ItemStack item = e.getItem().getItemStack();
		Coin c = getCoin(item);
		if(c == null) return;
		if(!c.canWithdraw()) return;
		e.setCancelled(true);
		e.getItem().remove();
		ItemMeta m = item.getItemMeta();
		
		NamespacedKey chain = new NamespacedKey(DenarEconomy.plugin, "chained");
        String chained = m.getPersistentDataContainer().get(chain, PersistentDataType.STRING);
        if(chained != null) {
        	return;
        }
        chain = new NamespacedKey(DenarEconomy.plugin, "chained_to");
        chained = m.getPersistentDataContainer().get(chain, PersistentDataType.STRING);
        
        if(chained != null) {
        	String[] items = chained.split("\\;");
        	for(String id : items) {
        		Entity chainedEntity = Bukkit.getEntity(UUID.fromString(id));
        		if(chainedEntity != null) chainedEntity.remove();
        	}
        }
        
        p.playSound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1f);
		NamespacedKey key = new NamespacedKey(DenarEconomy.plugin, "silent");
		Integer silent = m.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
		
		NamespacedKey value = new NamespacedKey(DenarEconomy.plugin, "customValue");
		Double customValue = m.getPersistentDataContainer().get(value, PersistentDataType.DOUBLE);
		double amount = 0.0;
		if(customValue != null) {
			amount = customValue;
		} else {
			amount = combinedValue(c, item);
		}
		
		key = new NamespacedKey(DenarEconomy.plugin, "sender");
		String sender = m.getPersistentDataContainer().get(key, PersistentDataType.STRING);
		boolean tax = true;
		if(sender != null) {
			if(p.getUniqueId().toString().equalsIgnoreCase(sender)) tax = false;
		}
		
		addMoney(p, amount, silent != null, tax);
	}

	@EventHandler
	public void onPlayerDeath(PlayerDeathEvent event) {
		Player victim = event.getEntity();
		Player killer = victim.getKiller();

		if (killer == null || killer == victim) return; // Not PvP or suicide

		boolean keepPouch = victim.hasMetadata(PouchDeathPolicy.KEEP_POUCH_METADATA);
		if (!PouchDeathPolicy.shouldDropPouch(event.getKeepInventory(), keepPouch)) {
			return;
		}

		PlayerData pd = pm.get(victim);
		if (pd == null) return;

		Account pouch = pd.getPouch();
		double balance = pouch.getBal();
		if (balance <= 0) return;

		pouch.change(-balance); // Remove from pouch
		dropItems(null, victim.getLocation(), balance); // Drop money at death location
	}
	
	@EventHandler
	public void coinSpawn(EntitySpawnEvent e) {
		if(!(e.getEntity() instanceof Item)) return;
		Item i = (Item) e.getEntity();
		Coin c = getCoin(i.getItemStack());
		if(c == null) return;
		if(!c.canWithdraw()) return;
		ItemStack item = i.getItemStack();
		ItemMeta m = item.getItemMeta();
		
		NamespacedKey key = new NamespacedKey(DenarEconomy.plugin, "customValue");
		if(m.getPersistentDataContainer().get(key, PersistentDataType.DOUBLE) == null) {
			i.setCustomNameVisible(true);
			i.setCustomName(MessageLoader.get("money.coin-name", "amount", combinedValue(c, i.getItemStack())));
		} else {
			double amount = m.getPersistentDataContainer().get(key, PersistentDataType.DOUBLE);
			if(amount > 0) {
				i.setCustomNameVisible(true);
				i.setCustomName(MessageLoader.get("money.coin-name", "amount", amount));
			}
		}
		
		i.setPickupDelay(10);
		
		key = new NamespacedKey(DenarEconomy.plugin, "nonstack");
		m.getPersistentDataContainer().set(key, PersistentDataType.STRING, UUID.randomUUID().toString());
		item.setItemMeta(m);
	}
}
