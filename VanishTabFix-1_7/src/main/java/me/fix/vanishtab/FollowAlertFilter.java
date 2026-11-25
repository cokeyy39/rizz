package me.fix.vanishtab;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * UltimateDonutSmp's friends system tells followers "X joined the game" / "X left the game" without
 * checking vanish. This filter:
 *  - drops those alerts for vanished players (staff who just joined are held ~1s until vanish is applied),
 *  - removes vanished players from the "online followed players" list,
 *  - sends followers a "left the game" alert when a player vanishes and "joined" when they unvanish.
 */
public class FollowAlertFilter implements Listener {

    private static final Pattern STRIP = Pattern.compile("(?i)&(#[0-9a-f]{6}|[0-9a-fk-or])");

    private final VanishTabFix plugin;
    private final ProtocolManager pm;
    private final Map<UUID, Long> joinTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> quitSuppress = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private volatile boolean bypass = false;
    private volatile String lastError = "none";

    private Object friendsManager;
    private Method getFollowers;
    private Method activityEnabled;
    private Object hideManager;
    private Method publicName;

    FollowAlertFilter(VanishTabFix plugin, ProtocolManager pm) throws Exception {
        this.plugin = plugin;
        this.pm = pm;
        Object main = Bukkit.getPluginManager().getPlugin("UltimateDonutSmp");
        friendsManager = main.getClass().getMethod("getFriendsManager").invoke(main);
        getFollowers = friendsManager.getClass().getMethod("getFollowersMap", UUID.class);
        Class<?> entry = Class.forName("com.bx.ultimateDonutSmp.models.FollowEntry", true, main.getClass().getClassLoader());
        activityEnabled = entry.getMethod("activityEnabled");
        try {
            hideManager = main.getClass().getMethod("getHideManager").invoke(main);
            publicName = hideManager.getClass().getMethod("publicName", Player.class);
        } catch (Exception ignored) {
            hideManager = null;
        }
    }

    void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        pm.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGH, PacketType.Play.Server.SYSTEM_CHAT) {
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
        return "dropped " + dropped.get() + ", follower messages sent " + sent.get() + ", last error: " + lastError;
    }

    boolean recentlyJoined(UUID id, long ms) {
        Long t = joinTimes.get(id);
        return t != null && System.currentTimeMillis() - t < ms;
    }

    // ---------- config ----------
    private String fmt(String key, String def) {
        return plugin.getConfig().getString("follow-alerts." + key, def);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("follow-alerts.enabled", true);
    }

    private static String plain(String legacy) {
        return STRIP.matcher(legacy).replaceAll("");
    }

    /** Returns the text matched by the token in `text`, or null if `text` doesn't fit the format. */
    private static String extract(String text, String format, String token) {
        String pl = plain(format);
        int i = pl.indexOf(token);
        if (i < 0) return null;
        String pre = pl.substring(0, i);
        String suf = pl.substring(i + token.length());
        if (text.length() >= pre.length() + suf.length() && text.startsWith(pre) && text.endsWith(suf)) {
            return text.substring(pre.length(), text.length() - suf.length());
        }
        return null;
    }

    String pub(Player p) {
        if (hideManager != null && publicName != null) {
            try {
                Object r = publicName.invoke(hideManager, p);
                if (r != null) return r.toString();
            } catch (Exception ignored) {
            }
        }
        return p.getName();
    }

    private Set<String> hiddenNames() {
        Set<String> out = new HashSet<>(plugin.vanishedNames());
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.vanished(p.getUniqueId())) out.add(pub(p).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // ---------- events ----------
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        joinTimes.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (plugin.wasVanished(p.getUniqueId()) || plugin.vanished(p.getUniqueId())) {
            long now = System.currentTimeMillis();
            quitSuppress.put(p.getName().toLowerCase(Locale.ROOT), now);
            quitSuppress.put(pub(p).toLowerCase(Locale.ROOT), now);
        }
        joinTimes.remove(p.getUniqueId());
    }

    // ---------- packet filter ----------
    private void handle(PacketEvent e) {
        if (bypass || !enabled()) return;
        Player viewer = e.getPlayer();
        if (viewer == null || viewer.hasPermission(VanishTabFix.SEE_PERM)) return;
        PacketContainer packet = e.getPacket();
        if (packet.getBooleans().size() > 0 && packet.getBooleans().read(0)) return; // action bar

        boolean viaString = false;
        String json;
        WrappedChatComponent wc = packet.getChatComponents().size() > 0 ? packet.getChatComponents().readSafely(0) : null;
        if (wc != null) {
            json = wc.getJson();
        } else {
            json = packet.getStrings().size() > 0 ? packet.getStrings().readSafely(0) : null;
            viaString = true;
        }
        if (json == null) return;
        String text = PlainTextComponentSerializer.plainText().serialize(GsonComponentSerializer.gson().deserialize(json)).trim();
        if (text.isEmpty()) return;

        // "X joined the game."
        String name = extract(text, fmt("join-format", ""), "{player}");
        if (name != null) {
            String low = name.toLowerCase(Locale.ROOT);
            if (hiddenNames().contains(low)) {
                e.setCancelled(true);
                dropped.incrementAndGet();
                return;
            }
            Player target = Bukkit.getPlayerExact(name);
            if (target != null && target.hasPermission(VanishTabFix.SEE_PERM) && recentlyJoined(target.getUniqueId(), 15000)) {
                // Staff just joined: their vanish may not be applied yet. Hold the alert and re-check.
                PacketContainer copy = packet.deepClone();
                e.setCancelled(true);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!viewer.isOnline() || !target.isOnline()) return;
                    if (plugin.vanished(target.getUniqueId())) {
                        dropped.incrementAndGet();
                        return;
                    }
                    try {
                        pm.sendServerPacket(viewer, copy, false);
                    } catch (Exception ex) {
                        lastError = ex.toString();
                    }
                }, 20L);
            }
            return;
        }

        // "X left the game."
        name = extract(text, fmt("quit-format", ""), "{player}");
        if (name != null) {
            String low = name.toLowerCase(Locale.ROOT);
            Long t = quitSuppress.get(low);
            boolean recentVanishQuit = t != null && System.currentTimeMillis() - t < 3000;
            if (recentVanishQuit || hiddenNames().contains(low)) {
                e.setCancelled(true);
                dropped.incrementAndGet();
            }
            return;
        }

        // "online followed players: A, B"
        String list = extract(text, fmt("online-format", ""), "{players}");
        if (list != null) {
            Set<String> hidden = hiddenNames();
            List<String> keep = new ArrayList<>();
            boolean removed = false;
            for (String n : list.split(",")) {
                String t = n.trim();
                if (t.isEmpty()) continue;
                if (hidden.contains(t.toLowerCase(Locale.ROOT))) removed = true;
                else keep.add(t);
            }
            if (!removed) return;
            String legacy = keep.isEmpty()
                    ? fmt("online-none-format", "")
                    : fmt("online-format", "").replace("{players}", String.join("&7, &f", keep));
            String newJson = GsonComponentSerializer.gson().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(legacy));
            if (viaString) packet.getStrings().write(0, newJson);
            else packet.getChatComponents().write(0, WrappedChatComponent.fromJson(newJson));
            dropped.incrementAndGet();
        }
    }

    // ---------- fake alerts for followers ----------
    /** left = true: "X left the game" (player vanished). left = false: "X joined the game" (player unvanished). */
    void notifyFollowers(Player target, boolean left) {
        if (!enabled()) return;
        try {
            String format = left ? fmt("quit-format", "") : fmt("join-format", "");
            Component msg = LegacyComponentSerializer.legacyAmpersand().deserialize(format.replace("{player}", pub(target)));
            Object map = getFollowers.invoke(friendsManager, target.getUniqueId());
            if (!(map instanceof Map<?, ?> followers)) return;
            for (Map.Entry<?, ?> en : followers.entrySet()) {
                if (!(en.getKey() instanceof UUID fid)) continue;
                Player f = Bukkit.getPlayer(fid);
                if (f == null || !f.isOnline() || f.hasPermission(VanishTabFix.SEE_PERM)) continue;
                try {
                    if (en.getValue() != null && !(boolean) activityEnabled.invoke(en.getValue())) continue;
                } catch (Exception ignored) {
                }
                bypass = true;
                try {
                    f.sendMessage(msg);
                    sent.incrementAndGet();
                } finally {
                    bypass = false;
                }
            }
        } catch (Exception ex) {
            lastError = ex.toString();
        }
    }
}
