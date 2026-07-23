package ru.haven.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import ru.haven.Haven;
import ru.haven.core.DataStore;

import java.util.UUID;

/**
 * PlaceholderAPI-расширение. Регистрируется только если PlaceholderAPI установлен.
 * Плейсхолдеры для вставки в TAB (NEZNAMY):
 *   %haven_tag%         — готовый цветной тег (⚠ ГРИФЕР / ★ Свой / ✦ АДМИН / пусто)
 *   %haven_reputation%  — число
 *   %haven_status%      — GRIEFER / NEUTRAL / TRUSTED / STAFF
 */
public class HavenExpansion extends PlaceholderExpansion {

    private final Haven plugin;
    private final DataStore store;

    public HavenExpansion(Haven plugin, DataStore store) {
        this.plugin = plugin; this.store = store;
    }

    @Override public String getIdentifier() { return "haven"; }
    @Override public String getAuthor() { return "mieto"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || params == null) return "";
        UUID u = player.getUniqueId();
        // PAPI присылает плейсхолдер «как в конфиге». TAB обычно использует lowercase.
        // Матчим оба варианта (lower/Title) без toLowerCase-allocation на каждый запрос.
        return switch (params) {
            case "tag", "Tag", "TAG" -> store.tag(u);
            case "reputation", "Reputation", "REPUTATION", "rep", "Rep", "REP" -> String.valueOf(store.reputation(u));
            case "status", "Status", "STATUS" -> store.status(u);
            default -> null;
        };
    }
}
