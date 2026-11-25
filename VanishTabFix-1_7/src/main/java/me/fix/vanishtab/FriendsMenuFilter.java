package me.fix.vanishtab;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UltimateDonutSmp's /friends menu marks every followed player "Online" without checking vanish.
 * This rewrites the menu items the viewer receives so a vanished player's head says "Offline".
 * It only changes what the client is sent, never the server-side inventory.
 */
public class FriendsMenuFilter {

    private final VanishTabFix plugin;
    private final ProtocolManager pm;
    private final AtomicLong rewritten = new AtomicLong();
    private volatile String lastError = "none";

    FriendsMenuFilter(VanishTabFix plugin, ProtocolManager pm) {
        this.plugin = plugin;
        this.pm = pm;
    }

    void register() {
        pm.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGH,
                PacketType.Play.Server.WINDOW_ITEMS, PacketType.Play.Server.SET_SLOT) {
            @Override
            public void onPacketSending(PacketEvent e) {
                try {
                    handle(e);
                } catch (Exception ex) {
                    lastError = ex.toString();
                }
            }
        });
    }

    String status() {
        return "menu entries rewritten " + rewritten.get() + ", last error: " + lastError;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("friends-menu.enabled", true);
    }

    private boolean isFriendsMenu(Player viewer) {
        InventoryHolder h = viewer.getOpenInventory().getTopInventory().getHolder();
        if (h == null) return false;
        String n = h.getClass().getName();
        return n.contains("FriendsMenu") || n.contains("FriendDetailMenu");
    }

    private Set<UUID> hidden() {
        Set<UUID> out = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.vanished(p.getUniqueId())) out.add(p.getUniqueId());
        }
        return out;
    }

    private void handle(PacketEvent e) {
        if (!enabled()) return;
        Player viewer = e.getPlayer();
        if (viewer == null || viewer.hasPermission(VanishTabFix.SEE_PERM)) return;
        PacketContainer packet = e.getPacket();
        if (packet.getIntegers().size() == 0 || packet.getIntegers().read(0) == 0) return; // 0 = own inventory
        if (!isFriendsMenu(viewer)) return;

        Set<UUID> hidden = hidden();
        if (hidden.isEmpty()) return;

        if (e.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
            List<ItemStack> items = packet.getItemListModifier().read(0);
            if (items == null) return;
            List<ItemStack> out = new ArrayList<>(items.size());
            boolean changed = false;
            for (ItemStack it : items) {
                ItemStack fixed = scrub(it, hidden);
                if (fixed != null) {
                    out.add(fixed);
                    changed = true;
                } else {
                    out.add(it);
                }
            }
            if (changed) packet.getItemListModifier().write(0, out);
        } else {
            ItemStack fixed = scrub(packet.getItemModifier().read(0), hidden);
            if (fixed != null) packet.getItemModifier().write(0, fixed);
        }
    }

    /** Returns a fixed copy of the item, or null if nothing needed changing. */
    private ItemStack scrub(ItemStack it, Set<UUID> hidden) {
        if (it == null || it.getType() != Material.PLAYER_HEAD || !it.hasItemMeta()) return null;
        ItemMeta meta = it.getItemMeta();
        if (!(meta instanceof SkullMeta sm)) return null;
        OfflinePlayer owner = sm.getOwningPlayer();
        if (owner == null || !hidden.contains(owner.getUniqueId())) return null;
        List<Component> lore = meta.lore();
        if (lore == null) return null;

        String online = plugin.getConfig().getString("friends-menu.online-word", "Online");
        String offline = plugin.getConfig().getString("friends-menu.offline-word", "Offline");
        TextReplacementConfig swap = TextReplacementConfig.builder()
                .matchLiteral(online)
                .replacement(Component.text(offline, NamedTextColor.RED))
                .build();

        List<Component> newLore = new ArrayList<>(lore.size());
        boolean changed = false;
        for (Component line : lore) {
            String plain = PlainTextComponentSerializer.plainText().serialize(line);
            if (plain.contains(online)) {
                newLore.add(line.replaceText(swap));
                changed = true;
            } else {
                newLore.add(line);
            }
        }
        if (!changed) return null;
        meta.lore(newLore);
        ItemStack copy = it.clone();
        copy.setItemMeta(meta);
        rewritten.incrementAndGet();
        return copy;
    }
}
