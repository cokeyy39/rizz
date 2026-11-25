package me.fix.vanishtab;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class VanishTabFix extends JavaPlugin {

    static final String SEE_PERM = "ultimatedonutsmp.staff.mode.seevanished";
    private ProtocolManager pm;
    private Object staffManager;
    private Method isVanished;
    private boolean takesUuid;
    private final Set<UUID> wasVanished = new HashSet<>();
    private volatile String lastError = "none";
    private volatile String lastTabError = "none";
    private int tickCount = 0;
    private long hidesApplied = 0;
    private long removalsSent = 0;
    private volatile Set<String> vanishedNameCache = Collections.emptySet();
    private final AtomicLong tabHidden = new AtomicLong();
    private final AtomicLong commandsBlocked = new AtomicLong();
    private volatile String lastTabCompleteError = "none";
    private FollowAlertFilter followFilter;
    private FriendsMenuFilter friendsMenuFilter;
    private AdvancementFilter advancementFilter;
    private GamemodeKeeper gamemodeKeeper;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pm = ProtocolLibrary.getProtocolManager();
        try {
            Object main = Bukkit.getPluginManager().getPlugin("UltimateDonutSmp");
            staffManager = main.getClass().getMethod("getStaffModeManager").invoke(main);
            for (Method m : staffManager.getClass().getMethods()) {
                if (m.getName().equals("isVanished") && m.getParameterCount() == 1) {
                    isVanished = m;
                    try { isVanished.setAccessible(true); } catch (Exception ignored) { }
                    takesUuid = m.getParameterTypes()[0] == UUID.class;
                    break;
                }
            }
        } catch (Exception e) {
            getLogger().severe("Could not hook StaffModeManager: " + e);
        }
        if (isVanished == null) {
            getLogger().severe("isVanished() not found, disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("Hooked " + isVanished.getDeclaringClass().getName() + "#isVanished("
                + isVanished.getParameterTypes()[0].getSimpleName() + "). Run /vanishtab to check status.");

        // Strip vanished players out of every tab-list packet sent to non-staff
        pm.addPacketListener(new PacketAdapter(this, ListenerPriority.HIGHEST, PacketType.Play.Server.PLAYER_INFO) {
            @Override
            public void onPacketSending(PacketEvent e) {
                Player viewer = e.getPlayer();
                if (viewer.hasPermission(SEE_PERM)) return;
                List<PlayerInfoData> list = e.getPacket().getPlayerInfoDataLists().read(1);
                List<PlayerInfoData> kept = new ArrayList<>();
                for (PlayerInfoData d : list) {
                    UUID id = d.getProfileId();
                    if (id != null && !id.equals(viewer.getUniqueId()) && vanished(id)) continue;
                    kept.add(d);
                }
                if (kept.size() == list.size()) return;
                if (kept.isEmpty()) { e.setCancelled(true); return; }
                e.getPacket().getPlayerInfoDataLists().write(1, kept);
            }
        });

        // Twice a second, make sure non-staff have no tab entry for vanished players
        Bukkit.getScheduler().runTaskTimer(this, this::syncTab, 10L, 10L);

        // Hide vanished players from the multiplayer-list count/hover
        getServer().getPluginManager().registerEvents(new ServerListHider(this), this);
        // Hide vanished players from command tab-completion (/msg, /tpa, /pay, ...)
        getServer().getPluginManager().registerEvents(new TabCompleteHider(this), this);
        // Typing a vanished player's name in a command says "player not online"
        getServer().getPluginManager().registerEvents(new CommandBlocker(this), this);
        // Friends/follow system: no "joined" alert for vanished players, "left" alert when you vanish
        try {
            followFilter = new FollowAlertFilter(this, pm);
            followFilter.register();
        } catch (Exception ex) {
            followFilter = null;
            getLogger().warning("Follow-alert filter could not start: " + ex);
        }
        // /friends menu: vanished players show as Offline
        try {
            friendsMenuFilter = new FriendsMenuFilter(this, pm);
            friendsMenuFilter.register();
        } catch (Exception ex) {
            friendsMenuFilter = null;
            getLogger().warning("Friends-menu filter could not start: " + ex);
        }
        // vanished players' advancement messages are hidden from people who can't see vanished players
        try {
            advancementFilter = new AdvancementFilter(this, pm);
            advancementFilter.register();
        } catch (Exception ex) {
            advancementFilter = null;
            getLogger().warning("Advancement filter could not start: " + ex);
        }
        // staff keep their gamemode when teleporting between dimensions
        try {
            gamemodeKeeper = new GamemodeKeeper(this);
            gamemodeKeeper.register();
        } catch (Exception ex) {
            gamemodeKeeper = null;
            getLogger().warning("Gamemode keeper could not start: " + ex);
        }
        // %vanishtab_online% placeholder: online count without vanished players
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new VanishExpansion(this).register();
        }
    }

    private void syncTab() {
        try {
            tickCount++;
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            List<Player> hidden = new ArrayList<>();
            Set<UUID> now = new HashSet<>();
            Set<String> names = new HashSet<>();
            for (Player p : online) {
                if (vanished(p.getUniqueId())) {
                    hidden.add(p);
                    now.add(p.getUniqueId());
                    names.add(p.getName().toLowerCase(Locale.ROOT));
                }
            }
            vanishedNameCache = names;

            // Anyone who just unvanished: re-add them to non-staff tab lists
            for (UUID id : new ArrayList<>(wasVanished)) {
                if (now.contains(id)) continue;
                Player back = Bukkit.getPlayer(id);
                if (back == null) continue;
                if (tickCount > 1) notifyStaff(back, "unvanish");
                if (followFilter != null && tickCount > 1) followFilter.notifyFollowers(back, false);
                for (Player viewer : online) {
                    if (viewer.equals(back) || viewer.hasPermission(SEE_PERM)) continue;
                    viewer.hidePlayer(this, back);
                    viewer.showPlayer(this, back);
                }
            }

            Set<UUID> newlyHidden = new HashSet<>(now);
            newlyHidden.removeAll(wasVanished);
            if (tickCount > 1) {
                for (UUID id : newlyHidden) {
                    Player t = Bukkit.getPlayer(id);
                    if (t == null) continue;
                    boolean justJoined = followFilter != null && followFilter.recentlyJoined(id, 5000);
                    notifyStaff(t, justJoined ? "joined-vanished" : "vanish");
                }
            }
            if (followFilter != null && tickCount > 1) {
                for (UUID id : newlyHidden) {
                    Player t = Bukkit.getPlayer(id);
                    // Just joined while vanished = they were never seen joining, so no "left" message
                    if (t != null && !followFilter.recentlyJoined(id, 5000)) followFilter.notifyFollowers(t, true);
                }
            }
            wasVanished.clear();
            wasVanished.addAll(now);

            if (hidden.isEmpty()) return;
            boolean resend = tickCount % 4 == 0; // safety resend every ~2s

            for (Player viewer : online) {
                if (viewer.hasPermission(SEE_PERM)) continue;
                List<UUID> toRemove = new ArrayList<>();
                for (Player target : hidden) {
                    if (target.equals(viewer)) continue;
                    // Native Bukkit hide: removes the tab entry on the client right away
                    if (viewer.canSee(target)) {
                        viewer.hidePlayer(this, target);
                        hidesApplied++;
                    }
                    if (resend || newlyHidden.contains(target.getUniqueId())) toRemove.add(target.getUniqueId());
                }
                if (toRemove.isEmpty()) continue;
                try {
                    PacketContainer rm = pm.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
                    rm.getUUIDLists().write(0, toRemove);
                    pm.sendServerPacket(viewer, rm);
                    removalsSent++;
                } catch (Exception ex) {
                    lastTabError = "packet: " + ex;
                }
            }
        } catch (Exception ex) {
            lastTabError = ex.toString();
            getLogger().warning("Tab sync failed: " + ex);
        }
    }

    @Override
    public void onDisable() {
        try {
            for (UUID id : wasVanished) {
                Player target = Bukkit.getPlayer(id);
                if (target == null) continue;
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (!viewer.equals(target)) viewer.showPlayer(this, target);
                }
            }
        } catch (Exception ignored) { }
    }

    /** Lowercase names of currently vanished online players (safe to call from async threads). */
    public Set<String> vanishedNames() {
        Set<String> out = new HashSet<>(vanishedNameCache);
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (vanished(p.getUniqueId())) out.add(p.getName().toLowerCase(Locale.ROOT));
            }
        } catch (Exception ex) {
            lastTabCompleteError = ex.toString();
        }
        return out;
    }

    /** Tells everyone who can see vanished players that someone vanished / came back (they never see it otherwise). */
    private void notifyStaff(Player who, String key) {
        if (!getConfig().getBoolean("vanish-notice.enabled", true)) return;
        String fmt = getConfig().getString("vanish-notice." + key, "");
        if (fmt == null || fmt.isBlank()) return;
        Component msg = LegacyComponentSerializer.legacyAmpersand().deserialize(fmt.replace("{player}", who.getName()));
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (s.equals(who) || !s.hasPermission(SEE_PERM)) continue;
            s.sendMessage(msg);
        }
    }

    /** The name other players see for this player (their disguise name if they have one). */
    String publicNameOf(Player p) {
        return followFilter != null ? followFilter.pub(p) : p.getName();
    }

    void noteCommandBlocked() {
        commandsBlocked.incrementAndGet();
    }

    void noteTabHidden(int n) {
        tabHidden.addAndGet(n);
    }

    void noteTabError(Exception ex) {
        lastTabCompleteError = ex.toString();
    }

    /** True if this player was vanished at the last tab sync (main thread). */
    public boolean wasVanished(UUID id) {
        return wasVanished.contains(id);
    }

    public boolean vanished(UUID id) {
        try {
            Object arg = takesUuid ? id : Bukkit.getPlayer(id);
            if (arg == null) return false;
            return (boolean) isVanished.invoke(staffManager, arg);
        } catch (Exception ex) {
            Throwable t = ex.getCause() != null ? ex.getCause() : ex;
            lastError = t.toString();
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("vanishtab.admin")) {
            sender.sendMessage("You don't have permission.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("VanishTabFix config reloaded.");
            return true;
        }
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (vanished(p.getUniqueId())) names.add(p.getName());
        sender.sendMessage("VanishTabFix " + getDescription().getVersion());
        sender.sendMessage("Hooked method: " + isVanished.getDeclaringClass().getSimpleName() + "#isVanished("
                + isVanished.getParameterTypes()[0].getSimpleName() + ")");
        sender.sendMessage("Online (real): " + Bukkit.getOnlinePlayers().size() + "  Vanished: " + names.size()
                + "  Shown count: " + (Bukkit.getOnlinePlayers().size() - names.size()));
        sender.sendMessage("Vanished players: " + (names.isEmpty() ? "none detected" : String.join(", ", names)));
        sender.sendMessage("PlaceholderAPI hook: " + (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null ? "yes" : "no"));
        int canSeeVanished = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission(SEE_PERM)) canSeeVanished++;
        sender.sendMessage("Viewers who can see vanished (staff/op): " + canSeeVanished);
        sender.sendMessage("Tab hides applied: " + hidesApplied + "  Removal packets sent: " + removalsSent);
        sender.sendMessage("Last error from isVanished: " + lastError);
        sender.sendMessage("Last tab error: " + lastTabError);
        sender.sendMessage("Tab-complete names hidden: " + tabHidden.get() + "  Commands blocked: " + commandsBlocked.get());
        sender.sendMessage("Last tab-complete error: " + lastTabCompleteError);
        sender.sendMessage("Follow alerts: " + (followFilter != null ? followFilter.status() : "not active"));
        sender.sendMessage("Friends menu: " + (friendsMenuFilter != null ? friendsMenuFilter.status() : "not active"));
        sender.sendMessage("Advancements: " + (advancementFilter != null ? advancementFilter.status() : "not active"));
        sender.sendMessage("Gamemode keeper: " + (gamemodeKeeper != null ? gamemodeKeeper.status() : "not active"));
        return true;
    }
}
