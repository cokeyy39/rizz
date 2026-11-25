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
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides "X made the advancement [Y]" (also "reached the goal" / "completed the challenge") when X is vanished,
 * for everyone who can't see vanished players. UltimateDonutSmp turns the vanilla announcement off and sends its
 * own message to every player without a vanish check, so this filters what each player is sent.
 */
public class AdvancementFilter implements Listener {

    private static final Pattern ADV = Pattern.compile(
            "(?i)^(.*?)\\s+(?:has\\s+)?(?:made the advancement|reached the goal|completed the challenge)\\b.*$");

    private final VanishTabFix plugin;
    private final ProtocolManager pm;
    private final AtomicLong dropped = new AtomicLong();
    private volatile String lastError = "none";

    AdvancementFilter(VanishTabFix plugin, ProtocolManager pm) {
        this.plugin = plugin;
        this.pm = pm;
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
        return "advancement messages hidden " + dropped.get() + ", last error: " + lastError;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("advancement-filter.enabled", true);
    }

    private static boolean mentions(String lowerText, String name) {
        if (name == null || name.isBlank()) return false;
        return Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(name.toLowerCase(Locale.ROOT)) + "(?![a-z0-9_])")
                .matcher(lowerText).find();
    }

    // ---- the message UltimateDonutSmp (or anything else) sends to each player
    private void handle(PacketEvent e) {
        if (!enabled()) return;
        Player viewer = e.getPlayer();
        if (viewer == null || viewer.hasPermission(VanishTabFix.SEE_PERM)) return;
        PacketContainer packet = e.getPacket();
        if (packet.getBooleans().size() > 0 && packet.getBooleans().read(0)) return; // action bar

        String json;
        WrappedChatComponent wc = packet.getChatComponents().size() > 0 ? packet.getChatComponents().readSafely(0) : null;
        if (wc != null) json = wc.getJson();
        else json = packet.getStrings().size() > 0 ? packet.getStrings().readSafely(0) : null;
        if (json == null) return;

        String text = PlainTextComponentSerializer.plainText().serialize(GsonComponentSerializer.gson().deserialize(json)).trim();
        Matcher m = ADV.matcher(text);
        if (!m.matches()) return;
        String head = m.group(1).toLowerCase(Locale.ROOT);

        for (Player v : Bukkit.getOnlinePlayers()) {
            if (v.equals(viewer) || !plugin.vanished(v.getUniqueId())) continue;   // you may see your own advancement
            if (mentions(head, v.getName()) || mentions(head, plugin.publicNameOf(v))) {
                e.setCancelled(true);
                dropped.incrementAndGet();
                return;
            }
        }
    }

    // ---- vanilla broadcast (only used if the announceAdvancements gamerule is on)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (!enabled()) return;
        Player p = e.getPlayer();
        if (!plugin.vanished(p.getUniqueId())) return;
        Component msg = e.message();
        if (msg == null) return;
        e.message(null);   // nobody gets the vanilla broadcast...
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (s.equals(p) || s.hasPermission(VanishTabFix.SEE_PERM)) s.sendMessage(msg);   // ...except staff and the player
        }
        dropped.incrementAndGet();
    }
}
