package ru.haven.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.haven.Settings;
import ru.haven.storage.Storage;
import ru.haven.storage.StorageWorker;
import ru.haven.util.BlockKey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Мозг плагина: in-memory состояние + логика доступа/репутации, поверх {@link Storage}.
 *
 * <h3>Контракт треда (важно)</h3>
 * Все read-операции (getOwner/canAccess/reputation/...) — lock-free через {@code ConcurrentHashMap}.
 * Все write-операции главного пути (blockOwners, players, trust) НЕ блокируют main:
 * сначала меняют in-memory state, потом enqueue async-задачу в {@link StorageWorker}.
 * Исключения: {@link #addIncident} и {@link #resolveIncident} — sync (редкие команды,
 * нужно вернуть id/bool вызывающему).
 */
public class DataStore {

    public static final class PlayerData {
        public String name;
        public int playtimeMin;
        public boolean bypass;
        public boolean verified;
        /** Последний вход (epoch ms); 0 = неизвестно/legacy. Для decay заброшенных построек. */
        public long lastLoginTs;
        PlayerData(String name, int playtimeMin, boolean bypass, boolean verified, long lastLoginTs) {
            this.name = name; this.playtimeMin = playtimeMin; this.bypass = bypass;
            this.verified = verified; this.lastLoginTs = lastLoginTs;
        }
    }

    private final Plugin plugin;
    private final Storage db;
    private final StorageWorker worker;
    private final Settings settings;

    private final Map<BlockKey, UUID> blockOwners = new ConcurrentHashMap<>();
    // write-behind: накапливаем изменения владельцев блоков, сбрасываем в БД пакетом на async-потоке
    private final Object pendingLock = new Object();
    private Map<BlockKey, UUID> pendingBlockWrites = new HashMap<>();
    private Set<BlockKey> pendingBlockDeletes = new HashSet<>();
    private final Map<UUID, Set<UUID>> trust = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    /** reverse: UUID → последнее известное имя. Заменяет Bukkit.getOfflinePlayer на main-треде. */
    private final Map<UUID, String> uuidToName = new ConcurrentHashMap<>();
    /** Игроки, чьи поля (playtime/bypass/verified/name) изменились с прошлого flush — flush только их. */
    private final Set<UUID> dirtyPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> incidentCounts = new ConcurrentHashMap<>();
    private final Set<UUID> staffUuids = ConcurrentHashMap.newKeySet();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> notifyCd = new ConcurrentHashMap<>();
    private final Map<String, Long> reportCd = new ConcurrentHashMap<>();
    private final Map<String, Long> pvpInquiryCd = new ConcurrentHashMap<>();
    /** Rolling 24h окно отправленных репортов на репортёра — для дневного лимита (L1 anti-spam). */
    private final Map<UUID, Deque<Long>> dailyReportTimes = new ConcurrentHashMap<>();
    /** Последний момент активности (move/place/break/interact) — для anti-AFK (L2 anti-spam). */
    private final Map<UUID, Long> lastActiveAt = new ConcurrentHashMap<>();
    /** Владельцы, чьи постройки decay'нулись (защита снята). canAccess читает O(1). */
    private final Set<UUID> decayedOwners = ConcurrentHashMap.newKeySet();
    /** Момент старта плагина — для grace-периода после рестарта (защита от downtime-артефакта). */
    private volatile long startupTs = System.currentTimeMillis();

    /** In-memory аккумулятор оффлайн-вторжений (write-behind; flush батчем в БД). */
    private static final class IntrAccum {
        final UUID owner, actor; final String action, world;
        int x, y, z, count; long ts;
        IntrAccum(UUID owner, UUID actor, String action, String world) {
            this.owner = owner; this.actor = actor; this.action = action; this.world = world;
        }
    }
    private final Map<String, IntrAccum> pendingIntrusions = new HashMap<>();
    private final Object intrusionLock = new Object();

    /** PvP inquiries: in-memory, не персистится. */
    private final ru.haven.core.PvpInquiry.Registry pvpInquiries = new ru.haven.core.PvpInquiry.Registry();
    public ru.haven.core.PvpInquiry.Registry pvpInquiries() { return pvpInquiries; }

    private final java.util.logging.Logger logger;

    public DataStore(Plugin plugin, Storage db, StorageWorker worker, Settings settings) {
        this.plugin = plugin; this.db = db; this.worker = worker; this.settings = settings;
        this.logger = (plugin != null) ? plugin.getLogger() : java.util.logging.Logger.getLogger("Haven");
    }

    /** Доступ к backend для команд (diag / migrate). */
    public Storage storage() { return db; }

    /** Доступ к worker'у для команд (diag) и тестов. */
    public StorageWorker worker() { return worker; }

    public Settings settings() { return settings; }

    // ---- логирование ----
    public boolean isDebug() { return settings.debug; }
    public void debug(String msg) { if (settings.debug) logger.info("[DEBUG] " + msg); }
    /** Lazy-вариант: строка собирается ТОЛЬКО если debug=true (избегаем alloc на hot path). */
    public void debug(Supplier<String> msg) { if (settings.debug) logger.info("[DEBUG] " + msg.get()); }
    public void info(String msg) { logger.info(msg); }

    public void loadAll() {
        Map<UUID, UUID> uuidPool = new HashMap<>(); // интернинг владельцев: одна копия UUID на игрока
        for (Map.Entry<BlockKey, UUID> e : db.loadBlocks().entrySet()) {
            blockOwners.put(e.getKey(), uuidPool.computeIfAbsent(e.getValue(), x -> x));
        }
        trust.putAll(db.loadTrust());
        Map<UUID, Object[]> pm = new HashMap<>();
        db.loadPlayers(pm);
        for (Map.Entry<UUID, Object[]> e : pm.entrySet()) {
            Object[] v = e.getValue();
            String name = (String) v[0];
            long lastLogin = v.length > 4 && v[4] != null ? ((Number) v[4]).longValue() : 0L;
            players.put(e.getKey(), new PlayerData(name, (Integer) v[1], (Boolean) v[2], (Boolean) v[3], lastLogin));
            if (name != null) {
                nameIndex.put(name.toLowerCase(Locale.ROOT), e.getKey());
                uuidToName.put(e.getKey(), name);
            }
        }
        incidentCounts.putAll(db.loadIncidentCounts());
        recomputeStaffFromNames();
    }

    /** Сопоставить ники из конфига с UUID известных игроков (без обращения к серверу — тестируемо). */
    public void recomputeStaffFromNames() {
        staffUuids.clear();
        for (String name : settings.staffNames) {
            UUID u = nameIndex.get(name);
            if (u != null) staffUuids.add(u);
        }
    }

    public void reloadStaff() {
        recomputeStaffFromNames();
        for (Player p : Bukkit.getOnlinePlayers()) resolveStaff(p);
    }

    // ---- players ----
    public PlayerData getOrCreate(UUID uuid, String name) {
        boolean[] created = {false};
        PlayerData d = players.computeIfAbsent(uuid, k -> {
            created[0] = true;
            return new PlayerData(name, 0, false, false, 0L);
        });
        if (created[0]) dirtyPlayers.add(uuid);
        if (name != null && !name.equals(d.name)) {
            d.name = name;
            dirtyPlayers.add(uuid);
        }
        if (name != null) {
            nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
            uuidToName.put(uuid, name);
        }
        return d;
    }

    public PlayerData getPlayer(UUID uuid) { return players.get(uuid); }

    public UUID uuidByName(String name) {
        if (name == null) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        return nameIndex.get(name.toLowerCase(Locale.ROOT));
    }

    /** Имя игрока из in-memory индекса (без потенциально блокирующего Bukkit.getOfflinePlayer). */
    public String nameOf(UUID uuid) {
        if (uuid == null) return null;
        String n = uuidToName.get(uuid);
        if (n != null) return n;
        PlayerData d = players.get(uuid);
        return d != null ? d.name : null;
    }

    public void persistPlayer(UUID uuid) { dirtyPlayers.add(uuid); }

    /** В тик считаем playtime in-memory; persist делает периодический async-flush. */
    public void tickPlaytime() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            tickPlaytimeFor(p.getUniqueId(), p.getName(), now);
        }
    }

    /**
     * Тик одного игрока с явным «сейчас» — выделено для тестируемости anti-AFK (L2).
     * Возвращает {@code true} если playtime увеличился, {@code false} если игрок AFK.
     * Прод-код зовёт {@link #tickPlaytime()}.
     */
    public boolean tickPlaytimeFor(UUID uuid, String name, long nowMs) {
        PlayerData d = getOrCreate(uuid, name);
        if (settings.afkTimeoutMinutes > 0) {
            Long last = lastActiveAt.get(uuid);
            long timeoutMs = settings.afkTimeoutMinutes * 60_000L;
            if (last == null || nowMs - last > timeoutMs) return false; // AFK — не растёт
        }
        d.playtimeMin++;
        dirtyPlayers.add(uuid);
        return true;
    }

    // ---- anti-AFK (L2 anti-report-spam): отметка активности игрока ----

    /** Фиксирует активность игрока (move/place/break/interact/join). См. {@link ru.haven.listeners.ActivityListener}. */
    public void markActive(UUID uuid) {
        markActive(uuid, System.currentTimeMillis());
    }

    /** Тестируемый вариант с явным «сейчас» — для unit-тестов. */
    public void markActive(UUID uuid, long nowMs) {
        lastActiveAt.put(uuid, nowMs);
    }

    /** Очистить запись активности (на quit, чтобы Map не рос бесконечно на долгоживущем сервере). */
    public void clearActive(UUID uuid) {
        lastActiveAt.remove(uuid);
    }

    /** AFK ли игрок прямо сейчас — для {@code /access} и {@code /hv info}. */
    public boolean isAfk(UUID uuid) {
        return isAfk(uuid, System.currentTimeMillis());
    }

    /** Тестируемый вариант с явным «сейчас». */
    public boolean isAfk(UUID uuid, long nowMs) {
        if (settings.afkTimeoutMinutes <= 0) return false;
        Long last = lastActiveAt.get(uuid);
        if (last == null) return true;
        return nowMs - last > settings.afkTimeoutMinutes * 60_000L;
    }

    // ---- decay заброшенных построек (soft: защита снимается, UUID остаётся) ----

    /** Установить точку старта плагина (для grace-after-startup). Зовётся из onEnable; в тестах — для контроля. */
    public void setStartupTs(long ts) { this.startupTs = ts; }

    /** Зафиксировать вход игрока: обновить last_login_ts и снять decay-флаг (постройки снова под защитой). */
    public void markLogin(UUID uuid, String name) {
        PlayerData d = getOrCreate(uuid, name);
        d.lastLoginTs = System.currentTimeMillis();
        dirtyPlayers.add(uuid);
        decayedOwners.remove(uuid); // вернулся вовремя — защита восстановлена мгновенно
    }

    /**
     * Считаются ли постройки владельца «заброшенными» (защита снята). Чистая функция — для тестов.
     * Правила: фича выкл / staff / bypass / grace-after-startup / lastLogin==0 (никогда не видели
     * вход после апгрейда — безопасный грейс) → НЕ decayed.
     */
    public boolean isDecayed(UUID owner, long nowMs) {
        if (!settings.decayEnabled) return false;
        // Footgun-guard: soft-days <= 0 НЕ должно мгновенно «забрасывать» весь мир.
        if (settings.decaySoftDays <= 0) return false;
        if (settings.decayStaffExempt && isStaff(owner)) return false;
        if (settings.decayBypassExempt && isBypass(owner)) return false;
        if (nowMs - startupTs < settings.decayGraceAfterStartupMinutes * 60_000L) return false;
        PlayerData d = players.get(owner);
        long lastLogin = d == null ? 0L : d.lastLoginTs;
        if (lastLogin == 0L) return false; // legacy/новый — грейс до первого входа
        return lastLogin < nowMs - settings.decaySoftDays * 86_400_000L;
    }

    public boolean isDecayed(UUID owner) { return isDecayed(owner, System.currentTimeMillis()); }

    /** Сколько минут осталось до decay (для /access, /hv info). -1 = decay не применяется/exempt; 0 = уже decayed. */
    public long decayMinutesRemaining(UUID owner, long nowMs) {
        if (!settings.decayEnabled || settings.decaySoftDays <= 0) return -1;
        if (settings.decayStaffExempt && isStaff(owner)) return -1;
        if (settings.decayBypassExempt && isBypass(owner)) return -1;
        PlayerData d = players.get(owner);
        long lastLogin = d == null ? 0L : d.lastLoginTs;
        if (lastLogin == 0L) return -1;
        long decayAt = lastLogin + settings.decaySoftDays * 86_400_000L;
        if (nowMs >= decayAt) return 0;
        return (decayAt - nowMs) / 60_000L;
    }

    /**
     * Пересчитать множество decayed-владельцев (полный скан players). Зовётся периодически на async.
     * Не на hot path — canAccess читает готовый Set. Возвращает число decayed (для warn-логики).
     */
    public int recomputeDecayed() {
        long now = System.currentTimeMillis();
        Set<UUID> fresh = ConcurrentHashMap.newKeySet();
        for (UUID u : players.keySet()) {
            if (isDecayed(u, now)) fresh.add(u);
        }
        // НЕ clear()+addAll() — это создаёт окно, где параллельный canAccess видит пустой set
        // (и на миг запрещает доступ к реально-decayed). removeIf+addAll атомарны поэлементно.
        decayedOwners.removeIf(u -> !fresh.contains(u));
        decayedOwners.addAll(fresh);
        debug(() -> "DECAY recompute: " + decayedOwners.size() + " заброшенных владельцев");
        return decayedOwners.size();
    }

    public boolean isDecayedCached(UUID owner) { return decayedOwners.contains(owner); }
    public int decayedCount() { return decayedOwners.size(); }
    /** Сбросить кэш заброшенных (вызывается при выключении decay через /hv reload — защита возвращается). */
    public void clearDecayedCache() { decayedOwners.clear(); }

    // ---- offline intrusion summary («пока тебя не было…») ----

    /**
     * Зафиксировать попытку вторжения к ОФФЛАЙН-владельцу (write-behind, in-memory дедуп).
     * Дедуп по {@code (owner,actor,action,world)}: 30 сломанных блоков → одна запись с count=30.
     * DoS-кэп: при переполнении карты новые ключи отбрасываются (существующие продолжают расти).
     */
    public void recordIntrusion(UUID owner, UUID actor, String action, String world, int x, int y, int z) {
        if (!settings.offlineSummaryEnabled) return;
        if (owner == null || actor == null || owner.equals(actor)) return;
        String key = owner + "|" + actor + "|" + action + "|" + world;
        long now = System.currentTimeMillis();
        synchronized (intrusionLock) {
            IntrAccum a = pendingIntrusions.get(key);
            if (a == null) {
                if (pendingIntrusions.size() >= settings.offlineMaxPending) {
                    debug(() -> "INTRUSION буфер переполнен (" + settings.offlineMaxPending + ") — ключ отброшен");
                    return;
                }
                a = new IntrAccum(owner, actor, action, world);
                pendingIntrusions.put(key, a);
            }
            a.count++; a.x = x; a.y = y; a.z = z; a.ts = now;
        }
    }

    /** Снять snapshot аккумулятора и enqueue батч-запись в БД. O(N_keys) на main, write — в фоне. */
    public void flushIntrusions() {
        List<Storage.IntrusionRow> batch;
        synchronized (intrusionLock) {
            if (pendingIntrusions.isEmpty()) return;
            batch = new ArrayList<>(pendingIntrusions.size());
            for (IntrAccum a : pendingIntrusions.values()) {
                batch.add(new Storage.IntrusionRow(a.owner, a.actor, a.action, a.world, a.x, a.y, a.z, a.count, a.ts));
            }
            pendingIntrusions.clear();
        }
        if (worker != null) worker.submit(() -> db.recordIntrusions(batch));
        else db.recordIntrusions(batch);
    }

    /** TTL-очистка устаревших вторжений (старше offline-summary.ttl-hours). Зовётся периодически на async. */
    public void pruneIntrusions() {
        if (!settings.offlineSummaryEnabled) return;
        long cutoff = System.currentTimeMillis() - settings.offlineTtlHours * 3600_000L;
        int removed = db.pruneIntrusions(cutoff);
        if (removed > 0) debug(() -> "INTRUSION prune: удалено " + removed + " непросмотренных");
    }

    /**
     * Собрать готовые строки сводки для показа на входе И удалить их (показали — забыли).
     * Вызывать на async (делает запросы к БД). Пустой список — если нечего показать.
     */
    public List<String> buildAndClearSummary(UUID owner) {
        if (!settings.offlineSummaryEnabled) return List.of();
        long since = System.currentTimeMillis() - settings.offlineTtlHours * 3600_000L;
        List<Storage.IntrusionRow> rows = db.intrusionSummary(owner, since, settings.offlineMaxShown);
        if (rows.isEmpty()) return List.of();
        db.clearIntrusions(owner);
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>(rows.size());
        for (Storage.IntrusionRow r : rows) {
            String actorName = nameOf(r.actor);
            if (actorName == null) actorName = r.actor.toString().substring(0, 8);
            String ago = humanAgo(now - r.ts);
            String times = r.count > 1 ? " &8(×" + r.count + ")" : "";
            out.add("&8• &f" + actorName + " &7пытался " + r.action + " ваше" + times
                    + " &8в " + r.world + " " + r.x + "," + r.y + "," + r.z + " &8— " + ago + " назад");
        }
        return out;
    }

    /** Человекочитаемо «3ч», «2д», «45м» из миллисекунд (для сводки). */
    private static String humanAgo(long ms) {
        long m = ms / 60_000L;
        if (m < 60) return Math.max(1, m) + "м";
        long h = m / 60;
        if (h < 24) return h + "ч";
        return (h / 24) + "д";
    }

    /** Enqueue последних мутаций в writer-очередь. Для onDisable дрейн с таймаутом делает worker.shutdown(). */
    public void flush() {
        drainToDb();
        flushPlayersAsync();
        flushIntrusions();
    }

    /** Снимает snapshot с pending-блоков и enqueue в worker. O(1) на main; batch-write — в фоне. */
    public void drainToDb() {
        Map<BlockKey, UUID> bw; Set<BlockKey> bd;
        synchronized (pendingLock) {
            if (pendingBlockWrites.isEmpty() && pendingBlockDeletes.isEmpty()) return;
            bw = pendingBlockWrites; bd = pendingBlockDeletes;
            pendingBlockWrites = new HashMap<>(); pendingBlockDeletes = new HashSet<>();
        }
        if (worker != null) {
            worker.submit(() -> {
                db.flushBlocks(bw, bd);
                if (settings.debug) debug("flush: блоки +" + bw.size() + "/-" + bd.size());
            });
        } else {
            // legacy / тесты без worker — sync fallback.
            db.flushBlocks(bw, bd);
            if (settings.debug) debug("flush: блоки +" + bw.size() + "/-" + bd.size());
        }
    }

    /** Снимает snapshot dirty-игроков и enqueue их пакетную запись. O(N) на main только в подготовке rows. */
    public void flushPlayersAsync() {
        if (dirtyPlayers.isEmpty()) return;
        // Снимок dirty-UUID'ов + clear; новые изменения в процессе flush попадут в следующий цикл.
        List<UUID> snapshot = new ArrayList<>(dirtyPlayers);
        dirtyPlayers.clear();
        List<Storage.PlayerRow> rows = new ArrayList<>(snapshot.size());
        for (UUID u : snapshot) {
            PlayerData d = players.get(u);
            if (d != null) rows.add(new Storage.PlayerRow(u, d.name, d.playtimeMin, d.bypass, d.verified, d.lastLoginTs));
        }
        if (worker != null) {
            worker.submit(() -> {
                db.flushPlayers(rows);
                if (settings.debug) debug("flush: players " + rows.size());
            });
        } else {
            db.flushPlayers(rows);
            if (settings.debug) debug("flush: players " + rows.size());
        }
    }

    // ---- staff & bypass ----
    public void resolveStaff(Player p) {
        if (settings.staffNames.contains(p.getName().toLowerCase(Locale.ROOT))) {
            staffUuids.add(p.getUniqueId());
            debug(() -> "STAFF распознан: " + p.getName());
        }
    }

    public boolean isStaff(UUID uuid) { return staffUuids.contains(uuid); }

    public boolean isStaff(Player p) {
        return staffUuids.contains(p.getUniqueId())
                || p.hasPermission("haven.staff")
                || settings.staffNames.contains(p.getName().toLowerCase(Locale.ROOT));
    }

    public boolean isBypass(UUID uuid) {
        PlayerData d = players.get(uuid);
        return d != null && d.bypass;
    }

    public void setBypass(UUID uuid, String name, boolean value) {
        PlayerData d = getOrCreate(uuid, name);
        d.bypass = value;
        dirtyPlayers.add(uuid);
        debug(() -> "BYPASS " + (value ? "вкл" : "выкл") + ": " + name);
    }

    // ---- верификация (мгновенный доступ к опасным механикам без наигрыша) ----
    public boolean isVerified(UUID uuid) {
        PlayerData d = players.get(uuid);
        return d != null && d.verified;
    }

    public void setVerified(UUID uuid, String name, boolean value) {
        PlayerData d = getOrCreate(uuid, name);
        d.verified = value;
        dirtyPlayers.add(uuid);
        debug(() -> "VERIFY " + (value ? "вкл" : "выкл") + ": " + name);
    }

    public int playtimeOf(UUID uuid) {
        PlayerData d = players.get(uuid);
        return d == null ? 0 : d.playtimeMin;
    }

    /** Гейт опасных механик: 0 = открыто; &gt;0 = осталось минут наиграть; -1 = ограничено низким рейтингом. */
    public int gateState(UUID uuid, boolean exempt) {
        if (exempt || isBypass(uuid) || isVerified(uuid)) return 0;
        if (settings.sanctionsEnabled && reputation(uuid) <= settings.restrictBelow) return -1;
        if (settings.playtimeGateEnabled) {
            int pt = playtimeOf(uuid);
            if (pt < settings.requiredGateMinutes) return settings.requiredGateMinutes - pt;
        }
        return 0;
    }

    // ---- containers ----
    public UUID getOwner(BlockKey k) { return blockOwners.get(k); }

    public Map<BlockKey, UUID> blockOwnerSnapshot() {
        return new HashMap<>(blockOwners);
    }

    public void setOwner(BlockKey k, UUID owner) {
        blockOwners.put(k, owner);
        synchronized (pendingLock) { pendingBlockWrites.put(k, owner); pendingBlockDeletes.remove(k); }
    }

    public void removeOwner(BlockKey k) {
        if (blockOwners.remove(k) != null) {
            synchronized (pendingLock) { pendingBlockDeletes.add(k); pendingBlockWrites.remove(k); }
        }
    }

    /** Может ли игрок открыть/сломать контейнер с данным владельцем. */
    public boolean canAccess(Player p, UUID owner) {
        if (owner == null) return true;
        UUID u = p.getUniqueId();
        if (isStaff(p)) return true;
        if (u.equals(owner)) return true;
        if (isBypass(u)) return true;
        if (decayedOwners.contains(owner)) return true; // постройки заброшены (decay) — доступ свободен
        Set<UUID> t = trust.get(owner);
        return t != null && t.contains(u);
    }

    // ---- trust ----
    public boolean trustContains(UUID owner, UUID other) {
        Set<UUID> t = trust.get(owner);
        return t != null && t.contains(other);
    }

    public void addTrust(UUID owner, UUID trusted) {
        trust.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(trusted);
        if (worker != null) worker.submit(() -> db.addTrust(owner, trusted));
        else db.addTrust(owner, trusted);
        debug(() -> "TRUST add: " + owner + " -> " + trusted);
    }

    public void removeTrust(UUID owner, UUID trusted) {
        Set<UUID> t = trust.get(owner);
        if (t != null) t.remove(trusted);
        if (worker != null) worker.submit(() -> db.removeTrust(owner, trusted));
        else db.removeTrust(owner, trusted);
        debug(() -> "TRUST remove: " + owner + " -> " + trusted);
    }

    public Set<UUID> trustedOf(UUID owner) {
        return trust.getOrDefault(owner, Set.of());
    }

    // ---- reputation ----
    public int reputation(UUID uuid) {
        if (isStaff(uuid)) return settings.staffRep;
        PlayerData d = players.get(uuid);
        int pt = d == null ? 0 : d.playtimeMin;
        int inc = incidentCounts.getOrDefault(uuid, 0);
        long rep = (long) settings.repBase
                + (pt / 60L) * settings.repPerHour
                + (long) inc * settings.repPerIncident;
        return (int) Math.max(settings.repMin, Math.min(settings.repMax, rep));
    }

    public String status(UUID uuid) {
        if (isStaff(uuid)) return "STAFF";
        int r = reputation(uuid);
        if (r <= settings.grieferThreshold) return "GRIEFER";
        if (r >= settings.trustedThreshold) return "TRUSTED";
        return "NEUTRAL";
    }

    public String tag(UUID uuid) {
        String raw;
        switch (status(uuid)) {
            case "STAFF" -> raw = settings.tagStaff;
            case "GRIEFER" -> raw = settings.tagGriefer;
            case "TRUSTED" -> raw = settings.tagTrusted;
            default -> raw = settings.tagNeutral;
        }
        return ru.haven.util.Msg.color(raw);
    }

    // ---- incidents ----
    public int addIncident(UUID culprit, UUID reporter, String reason) {
        int weight = reporterCredible(reporter) ? 1 : 0;
        int id = db.addIncident(culprit, reporter, reason, System.currentTimeMillis(), weight);
        if (weight > 0) incidentCounts.merge(culprit, weight, Integer::sum);
        debug(() -> "REPORT #" + id + " (вес " + weight + "): culprit=" + culprit + " by=" + reporter + " : " + reason);
        return id;
    }

    /** Жалоба влияет на рейтинг только от «вменяемого» репортёра (анти-альт/sybil). */
    public boolean reporterCredible(UUID reporter) {
        if (isStaff(reporter) || isVerified(reporter) || isBypass(reporter)) return true;
        return playtimeOf(reporter) >= settings.minReporterPlaytimeMin
                && reputation(reporter) > settings.grieferThreshold;
    }

    public int incidentCount(UUID uuid) { return incidentCounts.getOrDefault(uuid, 0); }

    /**
     * Сколько РАЗНЫХ credible-репортёров пожаловались на {@code culprit} за окно
     * {@code sanctions.confirm-window-hours} (только OPEN-инциденты с {@code weight > 0}).
     * См. docs/PLAN-anti-report-spam.md L3.
     */
    public int distinctReportersInWindow(UUID culprit) {
        long sinceTs = System.currentTimeMillis() - settings.confirmWindowHours * 3600_000L;
        return db.distinctReportersFor(culprit, sinceTs);
    }

    /** Edge-trigger флаг «культприт сейчас в подтверждённом состоянии» — чтобы алерт не дублировался. */
    private final Set<UUID> confirmationActive = ConcurrentHashMap.newKeySet();

    /**
     * Должен ли админам уйти алерт {@code [ПОДТВЕРЖДЕНО]} прямо сейчас (L3).
     * Edge-trigger: возвращает {@code true} ТОЛЬКО на переходе «было ниже порога → стало
     * на или выше». При падении ниже порога (resolve-ы) состояние сбрасывается, следующее
     * пересечение снова сработает. На рестарте состояние теряется — приемлемо
     * (свежий админ получит напоминание о существующем «подтверждённом» грифере).
     */
    public boolean shouldFireConfirmation(UUID culprit) {
        int n = settings.confirmFromReporters;
        if (n < 2) return false;
        int distinct = distinctReportersInWindow(culprit);
        if (distinct >= n) {
            return confirmationActive.add(culprit); // true только при добавлении нового
        }
        confirmationActive.remove(culprit);
        return false;
    }

    public boolean resolveIncident(int id) {
        boolean ok = db.resolveIncident(id);
        if (ok) {
            incidentCounts.clear();
            incidentCounts.putAll(db.loadIncidentCounts());
            debug(() -> "RESOLVE инцидент #" + id);
        }
        return ok;
    }

    public java.util.List<String> listIncidents(UUID culprit, int limit) {
        return db.listIncidents(culprit, limit);
    }

    // ---- freeze (in-memory; сбрасывается при рестарте) ----
    public boolean frozenEmpty() { return frozen.isEmpty(); }
    public boolean isFrozen(UUID uuid) { return frozen.contains(uuid); }
    public void freeze(UUID uuid) { frozen.add(uuid); debug(() -> "FREEZE: " + uuid); }
    public void unfreeze(UUID uuid) { frozen.remove(uuid); debug(() -> "UNFREEZE: " + uuid); }

    // ---- inspector (/hv inspect) ----
    private final Set<UUID> inspecting = ConcurrentHashMap.newKeySet();
    public boolean toggleInspect(UUID u) { if (inspecting.remove(u)) return false; inspecting.add(u); return true; }
    public boolean isInspecting(UUID u) { return inspecting.contains(u); }

    // ---- stats (для /hv stats) ----
    public int ownedBlockCount() { return blockOwners.size(); }
    public int trackedPlayers() { return players.size(); }
    public int staffCount() { return staffUuids.size(); }
    public int frozenCount() { return frozen.size(); }
    public int openIncidents() { int s = 0; for (int v : incidentCounts.values()) s += v; return s; }

    // ---- cooldowns ----
    public boolean canNotify(UUID owner, UUID actor) {
        return hitCd(notifyCd, owner + ":" + actor, settings.notifyCooldown);
    }

    public boolean canReport(UUID reporter, UUID culprit) {
        return hitCd(reportCd, reporter + ":" + culprit, settings.reportCooldown);
    }

    /**
     * Дневной лимит на репортёра (анти-репорт-спам, L1).
     * Stafff/bypass/verified — без лимита; {@code maxReportsPerDay <= 0} — фича выключена.
     * Consumes one slot on {@code true}, не consumes на {@code false} — симметрично {@link #canReport}.
     */
    public boolean canReportToday(UUID reporter) {
        return canReportToday(reporter, System.currentTimeMillis());
    }

    /** Тестируемый вариант с явным «сейчас» — для unit-тестов 24h-окна. Прод-код зовёт no-arg перегрузку. */
    public boolean canReportToday(UUID reporter, long nowMs) {
        if (settings.maxReportsPerDay <= 0) return true;
        if (isStaff(reporter) || isVerified(reporter) || isBypass(reporter)) return true;
        long cutoff = nowMs - 86_400_000L;
        Deque<Long> deque = dailyReportTimes.computeIfAbsent(reporter, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < cutoff) deque.pollFirst();
            if (deque.size() >= settings.maxReportsPerDay) return false;
            deque.addLast(nowMs);
            return true;
        }
    }

    /** Сколько жалоб подал репортёр за последние 24ч (для /access и /hv info). */
    public int reportsToday(UUID reporter) {
        long cutoff = System.currentTimeMillis() - 86_400_000L;
        Deque<Long> deque = dailyReportTimes.get(reporter);
        if (deque == null) return 0;
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < cutoff) deque.pollFirst();
            return deque.size();
        }
    }

    /** Анти-спам: одна PvP-inquiry на пару (killer, victim) за {@code pvp.inquiry-cooldown-seconds}. */
    public boolean canPvpInquiry(UUID killer, UUID victim) {
        return hitCd(pvpInquiryCd, killer + ":" + victim, settings.pvpInquiryCooldownSec);
    }

    /**
     * Сбрасывает cooldown между парой — вызывается после успешного ответа жертвы (accept/complain).
     * Без сброса: 5-минутный cooldown держится даже после ответа, и следующая смерть от того же
     * убийцы в течение окна не даёт новую кнопку. С сбросом: ответил — следующий бой
     * сразу выдаёт новую inquiry.
     */
    public void clearPvpCooldown(UUID killer, UUID victim) {
        pvpInquiryCd.remove(killer + ":" + victim);
    }

    private boolean hitCd(Map<String, Long> map, String key, int seconds) {
        long now = System.currentTimeMillis();
        Long last = map.get(key);
        if (last != null && now - last < seconds * 1000L) return false;
        map.put(key, now);
        return true;
    }
}
