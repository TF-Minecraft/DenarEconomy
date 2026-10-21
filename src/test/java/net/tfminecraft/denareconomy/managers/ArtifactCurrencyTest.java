package net.tfminecraft.denareconomy.managers;

import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.loaders.CoinLoader;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.ItemChecker;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArtifactCurrencyTest {
    private ItemStack item(Material material, boolean artifact) {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer tags = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(material);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(tags);
        when(tags.has(new NamespacedKey("archaeo", "recovered_find"))).thenReturn(artifact);
        return item;
    }

    @Test void artifactsAreNeverRecognisedAsMoneyAcrossBankableMaterials() {
        MoneyManager manager = new MoneyManager();
        for (Material material : List.of(Material.GOLD_INGOT, Material.GOLD_NUGGET, Material.IRON_NUGGET)) {
            assertNull(manager.getCoin(item(material, true)));
        }
    }

    @Test void rightClickingAnArtifactDoesNotDispatchAMaterialDeposit() {
        MoneyManager manager = new MoneyManager();
        ItemStack charm = item(Material.GOLD_INGOT, true);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(charm);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        for (Action action : List.of(Action.RIGHT_CLICK_BLOCK, Action.RIGHT_CLICK_AIR)) {
            when(event.getAction()).thenReturn(action);
            // No server is running: accidentally dispatching the conversion event would fail.
            assertDoesNotThrow(() -> manager.depositMaterials(event));
        }
        verify(charm, never()).setAmount(anyInt());
    }

    @Test void pickingUpAnArtifactLeavesTheItemEntityForNormalPickup() {
        EntityPickupItemEvent event = mock(EntityPickupItemEvent.class);
        Item entity = mock(Item.class);
        ItemStack charm = item(Material.GOLD_INGOT, true);
        when(event.getEntity()).thenReturn(mock(Player.class));
        when(event.getItem()).thenReturn(entity);
        when(entity.getItemStack()).thenReturn(charm);
        new MoneyManager().pickupCoin(event);
        verify(event, never()).setCancelled(true);
        verify(entity, never()).remove();
    }

    @Test void ordinaryConfiguredGoldStillMatchesItsCurrencyDefinition() {
        ItemStack gold = item(Material.GOLD_INGOT, false);
        ItemAPI api = mock(ItemAPI.class);
        ItemChecker checker = mock(ItemChecker.class);
        Coin coin = mock(Coin.class);
        when(api.getChecker()).thenReturn(checker);
        when(coin.getItem()).thenReturn("v.gold_ingot");
        when(checker.checkItemWithPath(gold, "v.gold_ingot")).thenReturn(true);
        try (MockedStatic<TLibs> tlibs = mockStatic(TLibs.class);
             MockedStatic<CoinLoader> coins = mockStatic(CoinLoader.class)) {
            tlibs.when(TLibs::getItemAPI).thenReturn(api);
            coins.when(CoinLoader::get).thenReturn(List.of(coin));
            assertSame(coin, new MoneyManager().getCoin(gold));
        }
        assertFalse(MoneyManager.isRecoveredArtifact(null));
        assertFalse(MoneyManager.isRecoveredArtifact(mock(ItemStack.class)));
    }
}
