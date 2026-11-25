package me.fix.vanishtab;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps a staff member's gamemode when they teleport to another world/dimension. Things like Multiverse's
 * per-world gamemode switch you to survival (or whatever the other world is set to) on arrival; this puts
 * you back to what you had, for a short window after the teleport.
 */
public class GamemodeKeeper implements Listener {

    private static final class Saved {
        final GameMode mode;
        final boolean flying;
        final long until;

        Saved(GameMode mode, boolean flying, long until) {
            this.mode = mode;
            this.flying = flying;
            this.until = until;
        }
    }

    private final VanishTabFix plugin;
    private final Map<UUID, Saved> saved = new ConcurrentHashMap<>();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong restored = new AtomicLong();

    GamemodeKeeper(VanishTabFix plugin) {
        this.plugin = plugin;
    }

    void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    String status() {
        return "gamemode changes blocked " + blocked.get() + ", restored " + restored.get();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("keep-gamemode.enabled", true);
    }

    private boolean applies(Player p) {
        String perm = plugin.getConfig().getString("keep-gamemode.permission", VanishTabFix.SEE_PERM);
        return perm == null || perm.isBlank() || p.hasPermission(perm);
    }

    private long window() {
        return Math.max(500L, plugin.getConfig().getLong("keep-gamemode.window-ms", 3000L));
    }

    /** Remember the gamemode the moment before they change world. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!enabled()) return;
        Location to = e.getTo();
        if (to == null || to.getWorld() == null || to.getWorld().equals(e.getFrom().getWorld())) return;
        Player p = e.getPlayer();
        if (!applies(p)) return;
        saved.put(p.getUniqueId(), new Saved(p.getGameMode(), p.isFlying(), System.currentTimeMillis() + window()));
    }

    /** Refuse gamemode switches that other plugins make during that window. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMode(PlayerGameModeChangeEvent e) {
        Saved s = saved.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        if (System.currentTimeMillis() > s.until) {
            saved.remove(e.getPlayer().getUniqueId());
            return;
        }
        if (e.getNewGameMode() == s.mode) return;
        if (e.getCause() == PlayerGameModeChangeEvent.Cause.COMMAND) return;   // a staff member typing /gamemode on purpose
        e.setCancelled(true);
        blocked.incrementAndGet();
    }

    /** Belt and braces: put the gamemode (and flight) back if something got through anyway. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        final Player p = e.getPlayer();
        final UUID id = p.getUniqueId();
        if (!saved.containsKey(id)) return;
        for (long delay : new long[]{1L, 5L, 20L}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Saved s = saved.get(id);
                if (s == null || System.currentTimeMillis() > s.until || !p.isOnline()) return;
                if (p.getGameMode() != s.mode) {
                    p.setGameMode(s.mode);
                    restored.incrementAndGet();
                }
                if (s.mode == GameMode.CREATIVE || s.mode == GameMode.SPECTATOR) {
                    if (!p.getAllowFlight()) p.setAllowFlight(true);
                    if (s.flying && !p.isFlying()) p.setFlying(true);
                }
            }, delay);
        }
    }
}
