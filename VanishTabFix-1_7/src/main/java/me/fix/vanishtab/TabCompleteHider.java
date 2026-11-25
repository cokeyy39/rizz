package me.fix.vanishtab;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.TabCompleteEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Removes vanished players' names from command tab-completion (/msg, /tpa, /pay, ...)
 * for anyone who is not allowed to see vanished players.
 */
public class TabCompleteHider implements Listener {
    private final VanishTabFix plugin;

    TabCompleteHider(VanishTabFix plugin) {
        this.plugin = plugin;
    }

    /** Names (lowercase) this viewer must not be offered, or empty. */
    private Set<String> hiddenFor(Player viewer) {
        if (viewer.hasPermission(VanishTabFix.SEE_PERM)) return Set.of();
        Set<String> names = plugin.vanishedNames();
        names.remove(viewer.getName().toLowerCase(Locale.ROOT));
        return names;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    // Brigadier suggestions: this is what almost every command ends up using on modern Paper
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSuggestions(AsyncPlayerSendSuggestionsEvent e) {
        try {
            Set<String> hide = hiddenFor(e.getPlayer());
            if (hide.isEmpty()) return;
            Suggestions original = e.getSuggestions();
            List<Suggestion> kept = new ArrayList<>();
            for (Suggestion s : original.getList()) {
                if (!hide.contains(clean(s.getText()))) kept.add(s);
            }
            int removed = original.getList().size() - kept.size();
            if (removed == 0) return;
            plugin.noteTabHidden(removed);
            e.setSuggestions(new Suggestions(original.getRange(), kept));
        } catch (Exception ex) {
            plugin.noteTabError(ex);
        }
    }

    // Legacy / plugin-provided completions
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTab(TabCompleteEvent e) {
        try {
            if (!(e.getSender() instanceof Player viewer)) return;
            Set<String> hide = hiddenFor(viewer);
            if (hide.isEmpty()) return;
            List<String> kept = new ArrayList<>(e.getCompletions());
            int before = kept.size();
            kept.removeIf(c -> hide.contains(clean(c)));
            if (kept.size() == before) return;
            plugin.noteTabHidden(before - kept.size());
            e.setCompletions(kept);
        } catch (Exception ex) {
            plugin.noteTabError(ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncTab(AsyncTabCompleteEvent e) {
        try {
            if (!(e.getSender() instanceof Player viewer)) return;
            Set<String> hide = hiddenFor(viewer);
            if (hide.isEmpty()) return;
            List<String> kept = new ArrayList<>(e.getCompletions());
            int before = kept.size();
            kept.removeIf(c -> hide.contains(clean(c)));
            if (kept.size() == before) return;
            plugin.noteTabHidden(before - kept.size());
            e.setCompletions(kept);
        } catch (Exception ex) {
            plugin.noteTabError(ex);
        }
    }
}
