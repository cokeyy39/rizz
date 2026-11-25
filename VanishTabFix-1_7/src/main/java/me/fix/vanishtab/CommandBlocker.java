package me.fix.vanishtab;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * If a non-staff player types a vanished player's name into a command, the command is
 * cancelled and they get the normal "player not online" message instead.
 */
public class CommandBlocker implements Listener {
    private final VanishTabFix plugin;

    CommandBlocker(VanishTabFix plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("block-commands.enabled", true)) return;

        Player viewer = e.getPlayer();
        if (viewer.hasPermission(VanishTabFix.SEE_PERM)) return;

        String[] parts = e.getMessage().trim().split("\\s+");
        if (parts.length < 2) return;

        String label = parts[0].startsWith("/") ? parts[0].substring(1) : parts[0];
        label = label.toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);

        Set<String> exempt = new HashSet<>();
        for (String s : cfg.getStringList("block-commands.exempt-commands")) {
            exempt.add(s.toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        }
        if (exempt.contains(label)) return;

        Set<String> hidden = plugin.vanishedNames();
        hidden.remove(viewer.getName().toLowerCase(Locale.ROOT));
        if (hidden.isEmpty()) return;

        int n = Math.max(1, cfg.getInt("block-commands.args-to-check", 2));
        for (int i = 1; i <= n && i < parts.length; i++) {
            if (hidden.contains(parts[i].toLowerCase(Locale.ROOT))) {
                e.setCancelled(true);
                String msg = cfg.getString("not-online-message", "&cPlayer not online.");
                viewer.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                plugin.noteCommandBlocked();
                return;
            }
        }
    }
}
