package ru.haven.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Опциональная интеграция с CoreProtect через <b>reflection</b> (без compile-time зависимости).
 *
 * <h3>Почему reflection, а не provided-dependency</h3>
 * <ul>
 *   <li>Сборка плагина не зависит от внешнего maven-репозитория CoreProtect и не ломается,
 *       если он недоступен.</li>
 *   <li>Робастность к версиям: вызовы идут через {@link Method}, несовпадение сигнатур
 *       деградирует в no-op (evidence=null), а не в краш.</li>
 *   <li>CoreProtect — это softdep: на сервере без него {@link #isAvailable()} = false,
 *       инцидент просто создаётся без доказательств.</li>
 * </ul>
 *
 * <p>Используется {@code CoreProtectAPI.performLookup(time, restrictUsers, excludeUsers,
 * restrictBlocks, excludeBlocks, actionList, radius, location)} + {@code parseResult(String[])}.
 * Сигнатуры стабильны с API v6+. Минимальную версию задаёт {@code coreprotect.min-api-version}.</p>
 */
public final class CoreProtectHook {

    private final Logger log;

    private Object api;                 // CoreProtectAPI instance
    private Method mPerformLookup;      // performLookup(...)
    private Method mParseResult;        // parseResult(String[])
    // ParseResult getters (lazy-инициализируются по первому результату):
    private Method prGetPlayer, prGetX, prGetY, prGetZ, prGetType, prGetActionId;
    private volatile boolean parseMethodsReady;

    private boolean available;
    private int apiVersion = -1;

    public CoreProtectHook(Logger log) {
        this.log = log != null ? log : Logger.getLogger("Haven");
    }

    public boolean isAvailable() { return available; }
    public int apiVersion() { return apiVersion; }

    /**
     * Подключиться к CoreProtect, если он установлен/включён и его API не ниже {@code minApiVersion}.
     * Безопасно вызывать на onEnable — любая ошибка деградирует в {@code available=false}.
     */
    public boolean init(int minApiVersion) {
        try {
            Plugin cp = Bukkit.getPluginManager().getPlugin("CoreProtect");
            if (cp == null) {
                log.info("CoreProtect не найден — доказательства к инцидентам отключены (это нормально).");
                return false;
            }
            Object a = cp.getClass().getMethod("getAPI").invoke(cp);
            if (a == null) return false;
            boolean enabled = (boolean) a.getClass().getMethod("isEnabled").invoke(a);
            if (!enabled) {
                log.warning("CoreProtect найден, но его API ещё не включён — evidence отключён.");
                return false;
            }
            apiVersion = (int) a.getClass().getMethod("APIVersion").invoke(a);
            if (apiVersion < minApiVersion) {
                log.warning("CoreProtect API v" + apiVersion + " ниже требуемой v" + minApiVersion
                        + " — evidence отключён (обнови CoreProtect).");
                return false;
            }
            mPerformLookup = a.getClass().getMethod("performLookup",
                    int.class, List.class, List.class, List.class, List.class, List.class, int.class, Location.class);
            mParseResult = a.getClass().getMethod("parseResult", String[].class);
            this.api = a;
            this.available = true;
            log.info("CoreProtect подключён (API v" + apiVersion + ") — доказательства к инцидентам включены.");
            return true;
        } catch (Throwable t) {
            log.warning("CoreProtect hook init failed (" + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + ") — evidence отключён.");
            available = false;
            return false;
        }
    }

    /**
     * Лукап действий игрока {@code culpritName} вокруг {@code center} за последние {@code seconds}.
     * Возвращает отформатированные строки (≤ maxLines) или пустой список (CP недоступен / ничего не нашли /
     * ошибка). НЕ бросает. Вызывать на ОТДЕЛЬНОМ потоке (лукап может занять секунды).
     */
    public List<String> lookup(String culpritName, Location center, int radius, int seconds, int maxLines) {
        if (!available || center == null || culpritName == null) return List.of();
        try {
            List<String> users = List.of(culpritName);
            List<Integer> actions = List.of(0, 1); // 0 = сломал, 1 = поставил
            @SuppressWarnings("unchecked")
            List<String[]> raw = (List<String[]>) mPerformLookup.invoke(
                    api, seconds, users, null, null, null, actions, radius, center);
            if (raw == null || raw.isEmpty()) return List.of();

            List<String> out = new ArrayList<>(Math.min(raw.size(), maxLines));
            for (String[] row : raw) {
                if (out.size() >= maxLines) break;
                Object pr = mParseResult.invoke(api, (Object) row);
                if (pr == null) continue;
                ensureParseMethods(pr);
                String player = String.valueOf(prGetPlayer.invoke(pr));
                int x = (int) prGetX.invoke(pr), y = (int) prGetY.invoke(pr), z = (int) prGetZ.invoke(pr);
                int actionId = (int) prGetActionId.invoke(pr);
                Object type = prGetType.invoke(pr);
                String act = switch (actionId) {
                    case 0 -> "сломал";
                    case 1 -> "поставил";
                    default -> "тронул";
                };
                out.add("  " + player + ": " + act + " " + type + " (" + x + "," + y + "," + z + ")");
            }
            if (raw.size() > out.size()) {
                out.add("  … всего " + raw.size() + " действий, показано " + out.size());
            }
            return out;
        } catch (Throwable t) {
            log.warning("CoreProtect lookup failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            return List.of();
        }
    }

    private void ensureParseMethods(Object pr) throws NoSuchMethodException {
        if (parseMethodsReady) return;
        Class<?> c = pr.getClass();
        prGetPlayer = c.getMethod("getPlayer");
        prGetX = c.getMethod("getX");
        prGetY = c.getMethod("getY");
        prGetZ = c.getMethod("getZ");
        prGetType = c.getMethod("getType");
        prGetActionId = c.getMethod("getActionId");
        parseMethodsReady = true;
    }
}
