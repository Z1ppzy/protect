package ru.haven;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Разобранный конфиг — читается один раз (и на /hv reload), чтобы листенеры не парсили строки на каждое событие. */
public class Settings {

    public String prefix;
    public boolean debug;
    public boolean blockExplosionDamage, preventFireSpread, protectFromLiquids, preventPistonMove;
    public boolean protectBlocks;
    public boolean protectEntities;
    public final Set<Material> containers = EnumSet.noneOf(Material.class);
    public int repBase, repPerHour, repPerIncident, repMin, repMax, staffRep, grieferThreshold, trustedThreshold;
    public String tagGriefer, tagTrusted, tagNeutral, tagStaff;
    public int notifyCooldown, reportCooldown;
    public final Set<String> staffNames = new HashSet<>();

    public boolean playtimeGateEnabled;
    public int requiredGateMinutes;
    /** AFK-таймаут в минутах: если игрок не двигался/не взаимодействовал столько — playtime не растёт. 0 = выключено. */
    public int afkTimeoutMinutes;
    public final Set<Material> gatedItems = EnumSet.noneOf(Material.class);
    public boolean sanctionsEnabled;
    public int restrictBelow, flagBelow, minReporterPlaytimeMin;
    /** Дневной лимит жалоб от одного репортёра. 0 = выключено. Stafff/bypass/verified — без лимита. */
    public int maxReportsPerDay;
    /** Сколько РАЗНЫХ credible-репортёров нужно для алерта [ПОДТВЕРЖДЕНО]. 0/1 = алерт выключен. */
    public int confirmFromReporters;
    /** Окно (часы), в котором считаем distinct-репортёров для подтверждения. */
    public int confirmWindowHours;

    // ---- Оффлайн-сводка вторжений ----
    public boolean offlineSummaryEnabled;
    public int offlineTtlHours, offlineMaxShown, offlineMaxPending;

    // ---- Decay заброшенных построек ----
    public boolean decayEnabled;
    public int decaySoftDays, decayGraceOnInstallDays, decayGraceAfterStartupMinutes, decayRecomputeMinutes;
    public boolean decayStaffExempt, decayBypassExempt;

    // ---- Welcome (приветствие новичку: чат-гайд + Dialog-окно) ----
    public boolean welcomeEnabled, welcomeOnFirstJoin, welcomeUseDialog;

    // ---- CoreProtect интеграция (доказательства к инцидентам) ----
    public boolean coreprotectEnabled;
    public int cpLookupRadius, cpWindowMinutes, cpMaxLines, cpMinApiVersion;

    // ---- Discord webhook (опционально; пусто = выключено) ----
    /** URL Discord-вебхука для алертов {@code [ПОДТВЕРЖДЕНО]}. Пусто = выключено. */
    public String discordWebhookUrl;
    /** Snowflake-id роли для пинга в алерте. Пусто = без пинга. Discord allowed_mentions защищает от @everyone. */
    public String discordMentionRoleId;

    // ---- storage backend (sqlite default; mysql opt-in) ----
    public String storageType;                // "sqlite" | "mysql" | "mariadb"
    public String mysqlHost, mysqlDatabase, mysqlUser, mysqlPassword, mysqlExtraParams;
    public int mysqlPort;
    public int mysqlPoolMax, mysqlPoolMinIdle, mysqlConnTimeoutMs, mysqlMaxLifetimeMs;
    public boolean mysqlUseSsl;

    // ---- pvp ----
    public boolean pvpEnabled;
    public boolean pvpLogKills;
    public int pvpInquiryWindowSec;
    public int pvpInquiryCooldownSec;
    public boolean pvpNotifyKillerOnComplain;
    public int pvpComplainWeight;

    /** Для тестов: пустые настройки, заполняются через {@link #load}. */
    public Settings() {}

    public Settings(Plugin plugin) {
        load(plugin.getConfig());
    }

    public void load(FileConfiguration c) {
        prefix = c.getString("prefix", "&8[&bHaven&8] &r");
        debug = c.getBoolean("debug", false);

        blockExplosionDamage = c.getBoolean("protection.block-explosion-damage", true);
        preventFireSpread = c.getBoolean("protection.prevent-fire-spread", true);
        protectFromLiquids = c.getBoolean("protection.protect-from-liquids", true);
        preventPistonMove = c.getBoolean("protection.prevent-piston-move", true);
        protectBlocks = c.getBoolean("protection.protect-blocks", true);
        protectEntities = c.getBoolean("protection.protect-entities", true);

        containers.clear();
        for (String s : c.getStringList("containers")) {
            Material m = Material.matchMaterial(s.toUpperCase(Locale.ROOT));
            if (m != null) containers.add(m);
        }

        repBase = c.getInt("reputation.base", 0);
        repPerHour = c.getInt("reputation.per-hour", 10);
        repPerIncident = c.getInt("reputation.per-incident", -25);
        repMin = c.getInt("reputation.min", -100);
        repMax = c.getInt("reputation.max", 1000);
        staffRep = c.getInt("reputation.staff", 9999);
        grieferThreshold = c.getInt("reputation.griefer-threshold", -50);
        trustedThreshold = c.getInt("reputation.trusted-threshold", 100);

        tagGriefer = c.getString("tags.griefer", "&c⚠ ГРИФЕР");
        tagTrusted = c.getString("tags.trusted", "&a★ Свой");
        tagNeutral = c.getString("tags.neutral", "");
        tagStaff = c.getString("tags.staff", "&b✦ АДМИН");

        notifyCooldown = c.getInt("cooldowns.notify-seconds", 30);
        reportCooldown = c.getInt("cooldowns.report-seconds", 60);

        staffNames.clear();
        for (String s : c.getStringList("staff")) staffNames.add(s.toLowerCase(Locale.ROOT));

        playtimeGateEnabled = c.getBoolean("playtime-gate.enabled", true);
        requiredGateMinutes = c.getInt("playtime-gate.required-hours", 8) * 60;
        afkTimeoutMinutes = c.getInt("playtime-gate.afk-timeout-minutes", 5);
        gatedItems.clear();
        for (String s : c.getStringList("playtime-gate.blocked-items")) {
            Material m = Material.matchMaterial(s.toUpperCase(Locale.ROOT));
            if (m != null) gatedItems.add(m);
        }
        sanctionsEnabled = c.getBoolean("sanctions.enabled", true);
        restrictBelow = c.getInt("sanctions.restrict-below", -50);
        flagBelow = c.getInt("sanctions.flag-below", -100);
        minReporterPlaytimeMin = c.getInt("sanctions.min-reporter-playtime-hours", 1) * 60;
        maxReportsPerDay = c.getInt("sanctions.max-reports-per-day", 5);
        confirmFromReporters = c.getInt("sanctions.confirm-from-reporters", 2);
        confirmWindowHours = c.getInt("sanctions.confirm-window-hours", 24);
        discordWebhookUrl = c.getString("discord.webhook-url", "");
        discordMentionRoleId = c.getString("discord.mention-role-id", "");

        offlineSummaryEnabled = c.getBoolean("offline-summary.enabled", true);
        offlineTtlHours = c.getInt("offline-summary.ttl-hours", 24);
        offlineMaxShown = c.getInt("offline-summary.max-entries-shown", 20);
        offlineMaxPending = c.getInt("offline-summary.max-pending-events", 5000);

        welcomeEnabled = c.getBoolean("welcome.enabled", true);
        welcomeOnFirstJoin = c.getBoolean("welcome.show-on-first-join", true);
        welcomeUseDialog = c.getBoolean("welcome.use-dialog", true);

        coreprotectEnabled = c.getBoolean("coreprotect.enabled", true);
        cpLookupRadius = c.getInt("coreprotect.lookup-radius-blocks", 50);
        cpWindowMinutes = c.getInt("coreprotect.lookup-window-minutes", 30);
        cpMaxLines = c.getInt("coreprotect.max-snippet-lines", 50);
        cpMinApiVersion = c.getInt("coreprotect.min-api-version", 8);

        decayEnabled = c.getBoolean("decay.enabled", false);
        decaySoftDays = c.getInt("decay.soft-days-inactive", 30);
        decayGraceOnInstallDays = c.getInt("decay.grace-on-install-days", 30);
        decayGraceAfterStartupMinutes = c.getInt("decay.grace-after-startup-minutes", 60);
        decayRecomputeMinutes = c.getInt("decay.recompute-interval-minutes", 5);
        decayStaffExempt = c.getBoolean("decay.staff-exempt", true);
        decayBypassExempt = c.getBoolean("decay.bypass-exempt", true);

        // storage: sqlite by default, mysql opt-in
        storageType = c.getString("storage.type", "sqlite").toLowerCase(Locale.ROOT);
        mysqlHost = c.getString("storage.mysql.host", "localhost");
        mysqlPort = c.getInt("storage.mysql.port", 3306);
        mysqlDatabase = c.getString("storage.mysql.database", "haven");
        mysqlUser = c.getString("storage.mysql.user", "haven");
        mysqlPassword = c.getString("storage.mysql.password", "");
        mysqlExtraParams = c.getString("storage.mysql.extra-params", "");
        mysqlUseSsl = c.getBoolean("storage.mysql.use-ssl", false);
        // HikariCP defaults — sourced from LuckPerms bukkit/config.yml (battle-tested).
        mysqlPoolMax = c.getInt("storage.mysql.pool.maximum-pool-size", 10);
        mysqlPoolMinIdle = c.getInt("storage.mysql.pool.minimum-idle", 10);
        mysqlConnTimeoutMs = c.getInt("storage.mysql.pool.connection-timeout-ms", 5000);
        mysqlMaxLifetimeMs = c.getInt("storage.mysql.pool.maximum-lifetime-ms", 1800000);

        // pvp: death-inquiry для жертв
        pvpEnabled = c.getBoolean("pvp.enabled", true);
        pvpLogKills = c.getBoolean("pvp.log-kills", true);
        pvpInquiryWindowSec = c.getInt("pvp.inquiry-window-seconds", 300);
        pvpInquiryCooldownSec = c.getInt("pvp.inquiry-cooldown-seconds", 300);
        pvpNotifyKillerOnComplain = c.getBoolean("pvp.notify-killer-on-complain", true);
        pvpComplainWeight = c.getInt("pvp.complain-weight", 1);
    }

    /** Контейнер ли это (по конфигу + любые шалкеры). */
    public boolean isProtectable(Material m) {
        return containers.contains(m) || m.name().endsWith("SHULKER_BOX");
    }
}
