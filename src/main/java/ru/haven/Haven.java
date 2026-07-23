package ru.haven;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import ru.haven.commands.HavenCommand;
import ru.haven.core.DataStore;
import ru.haven.hook.HavenExpansion;
import ru.haven.listeners.ActivityListener;
import ru.haven.listeners.BlockProtectionListener;
import ru.haven.listeners.ContainerListener;
import ru.haven.listeners.EntityProtectionListener;
import ru.haven.listeners.PlaytimeGateListener;
import ru.haven.listeners.EnvironmentListener;
import ru.haven.listeners.FreezeListener;
import ru.haven.listeners.InspectListener;
import ru.haven.listeners.PlayerListener;
import ru.haven.listeners.PvpListener;
import ru.haven.storage.SqliteStorage;
import ru.haven.storage.Storage;
import ru.haven.storage.StorageWorker;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Haven extends JavaPlugin {

    private Settings settings;
    private Storage db;
    private StorageWorker worker;
    private DataStore store;
    private ru.haven.util.CoreProtectHook cpHook;
    private java.util.concurrent.ExecutorService evidenceExecutor;
    private ru.haven.util.Messages messages;
    // Динамические (зависящие от toggle) планировщики — пересоздаются на /hv reload.
    private org.bukkit.scheduler.BukkitTask decayTask, intrusionFlushTask, intrusionPruneTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        updateConfig();
        settings = new Settings(this);

        messages = new ru.haven.util.Messages(this);
        messages.load(getConfig().getString("language", "ru"));

        try {
            db = ru.haven.storage.StorageFactory.create(this, settings, getLogger());
        } catch (Exception ex) {
            getLogger().severe("Не удалось инициализировать БД: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        worker = new StorageWorker(getLogger());
        // Fail-loud: при DEGRADED шлём один alert админам онлайн. Если их нет — просто SEVERE в логе
        // (уже сделано в StorageWorker.triggerDegraded). Бродкаст идёт на main через scheduler
        // (callback вызван из writer-треда, а sendMessage требует Bukkit-context для большинства плагинов).
        worker.setOnDegraded(() -> getServer().getScheduler().runTask(this, () -> {
            String msg = "§4[Haven] §cСТОРАДЖ В DEGRADED — мутации не пишутся в БД. См. консоль.";
            for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) {
                if (p.hasPermission("haven.admin")) p.sendMessage(msg);
            }
            getServer().getConsoleSender().sendMessage(msg);
        }));

        store = new DataStore(this, db, worker, settings);
        store.setStartupTs(System.currentTimeMillis());
        store.loadAll();
        getLogger().info("Загружено: " + store.ownedBlockCount() + " защищённых блоков, "
                + store.trackedPlayers() + " игроков, " + store.staffCount() + " стафф, "
                + store.openIncidents() + " открытых жалоб.");
        if (settings.debug) getLogger().info("Debug-режим ВКЛ (подробные логи в консоль).");

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new BlockProtectionListener(store), this);
        pm.registerEvents(new EntityProtectionListener(this, store), this);
        pm.registerEvents(new PlaytimeGateListener(store), this);
        pm.registerEvents(new ContainerListener(this, store), this);
        pm.registerEvents(new EnvironmentListener(this, store), this);
        pm.registerEvents(new PlayerListener(this, store), this);
        pm.registerEvents(new ActivityListener(store), this);
        pm.registerEvents(new FreezeListener(store), this);
        pm.registerEvents(new InspectListener(store), this);
        pm.registerEvents(new PvpListener(store), this);

        HavenCommand cmd = new HavenCommand(this, store);
        for (String c : new String[]{"trust", "untrust", "trustlist", "rep", "report", "access", "haven", "hpvp"}) {
            if (getCommand(c) != null) {
                getCommand(c).setExecutor(cmd);
                getCommand(c).setTabCompleter(cmd);
            }
        }

        // playtime: +1 минута каждые 60с (in-memory; sync-запись убрана — пакетный async-флаш ниже).
        getServer().getScheduler().runTaskTimer(this, store::tickPlaytime, 1200L, 1200L);
        // write-behind: пакетный сброс владельцев блоков/сущностей в БД на async-потоке (раз в 3с).
        getServer().getScheduler().runTaskTimerAsynchronously(this, store::drainToDb, 60L, 60L);
        // write-behind для playtime/bypass/verified: batch раз в 5 минут на async (при quit — сразу).
        getServer().getScheduler().runTaskTimerAsynchronously(this, store::flushPlayersAsync, 6000L, 6000L);
        // decay + offline-сводка: условные планировщики (зависят от toggle) — заодно
        // переживают /hv reload (см. restartDynamicSchedulers).
        restartDynamicSchedulers();

        // CoreProtect (опционально): доказательства к инцидентам. Reflection-hook, softdep.
        cpHook = new ru.haven.util.CoreProtectHook(getLogger());
        if (settings.coreprotectEnabled) {
            cpHook.init(settings.cpMinApiVersion);
            if (cpHook.isAvailable()) {
                evidenceExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "Haven-evidence");
                    t.setDaemon(true);
                    return t;
                });
            }
        }

        if (pm.getPlugin("PlaceholderAPI") != null) {
            new HavenExpansion(this, store).register();
            getLogger().info("PlaceholderAPI подключён: %haven_tag%, %haven_reputation%, %haven_status%");
        } else {
            getLogger().info("PlaceholderAPI не найден — плейсхолдеры для TAB недоступны (поставь PlaceholderAPI).");
        }

        getLogger().info("Haven v" + getDescription().getVersion() + " включён.");
    }

    @Override
    public void onDisable() {
        // Финальный flush: добавляем последние накопленные мутации в очередь...
        if (store != null) store.flush();
        // ...и ждём, пока writer-тред их вычерпнет, с таймаутом.
        if (worker != null) worker.shutdown(java.time.Duration.ofSeconds(10));
        if (evidenceExecutor != null) evidenceExecutor.shutdownNow();
        if (db != null) db.close();
    }

    /** Хук CoreProtect (может быть недоступен). */
    public ru.haven.util.CoreProtectHook coreProtect() { return cpHook; }

    /**
     * Снять CoreProtect-снимок действий культприта вокруг точки и приложить к инциденту (async).
     * No-op, если CoreProtect недоступен. Вызывается из {@code /report} и PvP-complain.
     */
    public void captureEvidence(int incidentId, String culpritName, org.bukkit.Location loc) {
        if (cpHook == null || !cpHook.isAvailable() || evidenceExecutor == null || loc == null) return;
        final int radius = settings.cpLookupRadius;
        final int seconds = settings.cpWindowMinutes * 60;
        final int maxLines = settings.cpMaxLines;
        evidenceExecutor.submit(() -> {
            try {
                java.util.List<String> lines = cpHook.lookup(culpritName, loc, radius, seconds, maxLines);
                if (lines.isEmpty()) return;
                String header = "CoreProtect (радиус " + radius + ", окно " + settings.cpWindowMinutes
                        + "мин, действия " + culpritName + "):";
                String evidence = header + "\n" + String.join("\n", lines);
                store.storage().saveIncidentEvidence(incidentId, evidence);
                store.debug(() -> "EVIDENCE сохранён к #" + incidentId + " (" + lines.size() + " строк)");
            } catch (Throwable t) {
                getLogger().warning("captureEvidence #" + incidentId + " failed: " + t.getMessage());
            }
        });
    }

    public Storage db() { return db; }

    public StorageWorker worker() { return worker; }

    public Settings settings() { return settings; }

    public DataStore store() { return store; }

    public ru.haven.util.Messages messages() { return messages; }

    /**
     * Показать приветствие игроку: нативное Dialog-окно (если {@code welcome.use-dialog}) или
     * чат-гайд. Dialog внутри сам деградирует в чат при несовместимости API. No-op, если
     * приветствие выключено в конфиге.
     */
    public void showWelcome(org.bukkit.entity.Player p) {
        if (!settings.welcomeEnabled) return;
        if (settings.welcomeUseDialog) {
            ru.haven.util.WelcomeDialog.show(p, messages);
        } else {
            messages.sendList(p, "welcome");
        }
    }

    /** Перечитать config.yml на лету (/hv reload). Возвращает число добавленных новых ключей. */
    public int reloadAll() {
        reloadConfig();
        int added = updateConfig();
        settings.load(getConfig());
        messages.load(getConfig().getString("language", "ru"));
        store.reloadStaff();
        // Применяем включение/выключение decay/offline без рестарта (пересоздаём таски).
        restartDynamicSchedulers();
        return added;
    }

    /**
     * (Пере)создать планировщики, зависящие от toggle'ов конфига — чтобы {@code /hv reload}
     * включал/выключал decay и offline-сводку на лету, без рестарта сервера.
     * Постоянные таски (playtime, write-behind блоков/игроков) живут отдельно в onEnable.
     */
    private void restartDynamicSchedulers() {
        if (decayTask != null) { decayTask.cancel(); decayTask = null; }
        if (intrusionFlushTask != null) { intrusionFlushTask.cancel(); intrusionFlushTask = null; }
        if (intrusionPruneTask != null) { intrusionPruneTask.cancel(); intrusionPruneTask = null; }

        if (settings.decayEnabled) {
            long periodTicks = Math.max(1, settings.decayRecomputeMinutes) * 1200L;
            decayTask = getServer().getScheduler().runTaskTimerAsynchronously(this, store::recomputeDecayed, 200L, periodTicks);
        } else if (store != null) {
            // decay выключили на лету → сбрасываем кэш заброшенных, чтобы защита вернулась сразу.
            store.clearDecayedCache();
        }

        if (settings.offlineSummaryEnabled) {
            intrusionFlushTask = getServer().getScheduler().runTaskTimerAsynchronously(this, store::flushIntrusions, 60L, 60L);
            intrusionPruneTask = getServer().getScheduler().runTaskTimerAsynchronously(this, store::pruneIntrusions, 1200L, 72000L);
        }
    }

    /**
     * Авто-дополнение конфига: берёт дефолтный config.yml из jar и добавляет в существующий файл
     * любые отсутствующие ключи (со значениями по умолчанию), НЕ трогая значения игрока.
     * Комментарии существующего файла сохраняются. Возвращает число добавленных ключей.
     */
    public int updateConfig() {
        InputStream in = getResource("config.yml");
        if (in == null) return 0;
        YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        FileConfiguration cur = getConfig();
        java.util.List<String> added = mergeMissingKeys(def, cur);
        for (String k : added) getLogger().info("config.yml: добавлен новый ключ '" + k + "'");
        if (!added.isEmpty()) {
            cur.options().parseComments(true);
            saveConfig();
            getLogger().info("config.yml обновлён: добавлено новых ключей — " + added.size() + ".");
        }
        return added.size();
    }

    /** Чистая логика merge (тестируемо): добавляет в cur листовые ключи из def, которых там нет; возвращает их список. */
    static java.util.List<String> mergeMissingKeys(YamlConfiguration def, FileConfiguration cur) {
        java.util.List<String> added = new java.util.ArrayList<>();
        for (String key : def.getKeys(true)) {
            if (def.isConfigurationSection(key)) continue; // секции создадутся при set листового ключа
            if (!cur.isSet(key)) {
                cur.set(key, def.get(key));
                added.add(key);
            }
        }
        return added;
    }
}
