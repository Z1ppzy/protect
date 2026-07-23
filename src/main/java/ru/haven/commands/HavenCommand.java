package ru.haven.commands;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.haven.Haven;
import ru.haven.Settings;
import ru.haven.core.DataStore;
import ru.haven.listeners.PlaytimeGateListener;
import ru.haven.storage.MysqlStorage;
import ru.haven.storage.SqliteStorage;
import ru.haven.storage.Storage;
import ru.haven.storage.StorageMigrator;
import ru.haven.util.BlockKey;
import ru.haven.util.Msg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class HavenCommand implements CommandExecutor, TabCompleter {

    private static final int DEFAULT_SCRUB_RADIUS = 32;
    private static final int MAX_SCRUB_RADIUS = 512;

    private final Haven plugin;
    private final DataStore store;

    public HavenCommand(Haven plugin, DataStore store) {
        this.plugin = plugin; this.store = store;
    }

    private String pfx() { return store.settings().prefix; }

    /** i18n-сообщения (player-facing). Админ/диагностика — хардкод-русский. */
    private ru.haven.util.Messages msg() { return plugin.messages(); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "trust" -> { return trust(sender, args, true); }
            case "untrust" -> { return trust(sender, args, false); }
            case "trustlist" -> { return trustlist(sender); }
            case "rep" -> { return rep(sender, args); }
            case "report" -> { return report(sender, args); }
            case "access" -> { return access(sender); }
            case "haven" -> {
                // /haven help — гайд новичку, доступен ВСЕМ (до admin-проверки).
                //   /haven help        → Dialog-окно (игроку), чат-список (консоли);
                //   /haven help chat   → всегда чат-гайд с кликабельными командами
                //                        (на эту под-команду ведёт кнопка из Dialog-окна).
                if (args.length >= 1 && args[0].equalsIgnoreCase("help")) {
                    boolean chat = args.length >= 2 && args[1].equalsIgnoreCase("chat");
                    if (chat || !(sender instanceof Player pl)) {
                        plugin.messages().sendList(sender, "welcome");
                    } else {
                        plugin.showWelcome(pl);
                    }
                    return true;
                }
                return admin(sender, args);
            }
            case "hpvp" -> { return pvpHandle(sender, args); }
            default -> { return false; }
        }
    }

    // ---- /trust /untrust ----
    private boolean trust(CommandSender sender, String[] args, boolean add) {
        if (!(sender instanceof Player p)) { msg().send(sender, "common.players-only"); return true; }
        if (args.length < 1) { msg().send(p, add ? "trust.usage-add" : "trust.usage-remove"); return true; }
        UUID target = store.uuidByName(args[0]);
        if (target == null) { msg().send(p, "common.player-not-found"); return true; }
        if (target.equals(p.getUniqueId())) { msg().send(p, "common.cannot-self"); return true; }
        if (store.isStaff(target)) { msg().send(p, "trust.is-staff"); return true; }

        if (add) {
            store.addTrust(p.getUniqueId(), target);
            msg().send(p, "trust.added", "name", args[0]);
        } else {
            store.removeTrust(p.getUniqueId(), target);
            msg().send(p, "trust.removed", "name", args[0]);
        }
        return true;
    }

    // ---- /trustlist ----
    private boolean trustlist(CommandSender sender) {
        if (!(sender instanceof Player p)) { msg().send(sender, "common.players-only"); return true; }
        var trusted = store.trustedOf(p.getUniqueId());
        if (trusted.isEmpty()) { msg().send(p, "trust.list-empty"); return true; }
        List<String> names = new ArrayList<>();
        for (UUID u : trusted) {
            String n = store.nameOf(u);
            names.add(n != null ? n : u.toString().substring(0, 8));
        }
        msg().send(p, "trust.list-header", "count", trusted.size(), "names", String.join(", ", names));
        return true;
    }

    // ---- /rep ----
    private boolean rep(CommandSender sender, String[] args) {
        UUID target;
        String name;
        if (args.length >= 1) {
            target = store.uuidByName(args[0]);
            name = args[0];
            if (target == null) { msg().send(sender, "common.player-not-found"); return true; }
        } else if (sender instanceof Player p) {
            target = p.getUniqueId();
            name = p.getName();
        } else { Msg.send(sender, pfx() + "&7Использование: /rep ник"); return true; }

        // tag — legacy (из config tags + TAB/PAPI), поэтому строка собирается через Msg.send,
        // но «колхозные» скобки убраны: разделитель ·· вместо (…, жалоб: …).
        Msg.send(sender, pfx() + "&7Репутация &f" + name + " &8·· " + store.tag(target)
                + " &8·· &f" + store.reputation(target) + " &7очков, жалоб &f" + store.incidentCount(target));
        return true;
    }

    // ---- /report ----
    private boolean report(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { msg().send(sender, "common.players-only"); return true; }
        if (args.length < 1) { msg().send(p, "report.usage"); return true; }
        UUID target = store.uuidByName(args[0]);
        if (target == null) { msg().send(p, "common.player-not-found"); return true; }
        if (target.equals(p.getUniqueId())) { msg().send(p, "report.cannot-self"); return true; }
        if (store.isStaff(target)) { msg().send(p, "report.is-staff"); return true; }
        if (!store.canReport(p.getUniqueId(), target)) { msg().send(p, "report.cooldown"); return true; }
        if (!store.canReportToday(p.getUniqueId())) {
            msg().send(p, "report.daily-limit", "limit", store.settings().maxReportsPerDay);
            return true;
        }

        String reason = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "не указана";
        int id = store.addIncident(target, p.getUniqueId(), reason);
        msg().send(p, "report.sent", "id", id);
        // CoreProtect: приложить снимок действий культприта вокруг точки репорта (async, no-op если CP нет).
        plugin.captureEvidence(id, args[0], p.getLocation());

        String crit = (store.settings().sanctionsEnabled && store.reputation(target) <= store.settings().flagBelow) ? "&4[КРИТ] " : "";
        String alert = pfx() + crit + "&c[репорт #" + id + "] &f" + p.getName() + " &7→ &f" + args[0] + " &7: " + reason;
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("haven.admin")) Msg.send(staff, alert);
        }

        // Предупреждаем нарушителя (без имени репортёра, чтобы не провоцировать месть)
        Player culprit = Bukkit.getPlayer(target);
        if (culprit != null && culprit.isOnline()) {
            msg().send(culprit, "report.culprit-warned", "rep", store.reputation(target));
        }

        // L3: алерт [ПОДТВЕРЖДЕНО] при N независимых credible-репортёрах в окне.
        maybeFireConfirmedAlert(target, args[0]);
        return true;
    }

    /**
     * L3 anti-report-spam: если на культприта уже жаловались N разных credible-игроков
     * за окно {@code confirm-window-hours} — отдельный алерт онлайн-админам. Edge-trigger:
     * не дублируется, пока счётчик не упал ниже порога (например, после resolve).
     */
    private void maybeFireConfirmedAlert(UUID culprit, String culpritName) {
        if (!store.shouldFireConfirmation(culprit)) return;
        int n = store.distinctReportersInWindow(culprit);
        int hours = store.settings().confirmWindowHours;
        String msg = pfx() + "&4&l[ПОДТВЕРЖДЕНО] &c" + culpritName
                + " &7получил жалобы от &f" + n + " &7разных репортёров за &f" + hours
                + "ч. &7Стоит проверить — &f/hv info " + culpritName;
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("haven.admin")) Msg.send(staff, msg);
        }
        store.info("[CONFIRMED] " + culpritName + ": " + n + " distinct reporters in " + hours + "h");
        // Discord webhook (опционально — пусто = выкл). Async + fail-soft внутри.
        ru.haven.util.DiscordNotifier.sendConfirmedAlert(
                culpritName, n, hours,
                store.settings().discordWebhookUrl,
                store.settings().discordMentionRoleId,
                plugin.getLogger());
    }

    // ---- /access ---- (player-facing → MiniMessage без «колхозных» скобок)
    private boolean access(CommandSender sender) {
        if (!(sender instanceof Player p)) { msg().send(sender, "common.players-only"); return true; }
        int pt = store.playtimeOf(p.getUniqueId());
        int st = store.gateState(p.getUniqueId(), store.isStaff(p));
        // L2: AFK-статус (если включён) — чтобы игрок понимал, почему playtime не растёт.
        String afk = (store.settings().afkTimeoutMinutes > 0 && store.isAfk(p.getUniqueId()))
                ? " <dark_gray>··</dark_gray> <red>сейчас AFK, время не идёт" : "";
        msg().sendRaw(p, "<gray>Наиграно <white>" + (pt / 60) + "ч " + (pt % 60) + "м</white>" + afk);
        if (st == 0) {
            msg().sendRaw(p, "<gradient:#56ab2f:#a8e063>Доступ к опасным механикам открыт.</gradient>");
        } else if (st < 0) {
            msg().sendRaw(p, "<red>Доступ ограничен из-за низкой репутации <dark_gray>··</dark_gray> <white>"
                    + store.reputation(p.getUniqueId()) + "</white><red>. Восстановите репутацию.");
        } else {
            msg().sendRaw(p, "<gray>До доступа осталось <white>" + PlaytimeGateListener.fmt(st)
                    + "</white> <gray>активной игры, всего нужно <white>" + (store.settings().requiredGateMinutes / 60) + "ч</white><gray>.");
        }
        // Decay: предупредить владельца, когда его постройки разблокируются от неактивности.
        if (store.settings().decayEnabled) {
            long mins = store.decayMinutesRemaining(p.getUniqueId(), System.currentTimeMillis());
            if (mins == 0) {
                msg().sendRaw(p, "<gradient:#cb2d3e:#ef473a>Ваши постройки сейчас открыты</gradient> <gray>из-за неактивности — заходите чаще, чтобы вернуть защиту.");
            } else if (mins > 0) {
                msg().sendRaw(p, "<gray>Защита построек снимется через <white>" + fmtDaysHours(mins)
                        + "</white> <gray>без вашего входа.");
            }
        }
        return true;
    }

    /** Человекочитаемо «Xд Yч» из минут (для decay-остатка). */
    private static String fmtDaysHours(long minutes) {
        long h = minutes / 60;
        long d = h / 24;
        if (d > 0) return d + "д " + (h % 24) + "ч";
        if (h > 0) return h + "ч " + (minutes % 60) + "м";
        return minutes + "м";
    }

    // ---- /haven (admin) ----
    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("haven.admin")) { Msg.send(sender, pfx() + "&cНет прав."); return true; }
        if (args.length == 0) { help(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                int added = plugin.reloadAll();
                Msg.send(sender, pfx() + "&aКонфиг перезагружен."
                        + (added > 0 ? " &7(добавлено новых ключей: &f" + added + "&7)" : ""));
            }
            case "debug" -> {
                boolean val = args.length >= 2 ? args[1].equalsIgnoreCase("on") : !store.isDebug();
                store.settings().debug = val;
                Msg.send(sender, pfx() + "&aDebug-логи в консоль: " + (val ? "&aВКЛ" : "&cВЫКЛ")
                        + " &7(для постоянного — debug в config.yml).");
                store.info("Debug-режим " + (val ? "включён" : "выключен") + " (" + sender.getName() + ")");
            }
            case "stats" -> Msg.send(sender, pfx() + "&7Haven: &f" + store.ownedBlockCount() + " &7блоков под защитой, &f"
                    + store.trackedPlayers() + " &7игроков, &f" + store.staffCount() + " &7стафф, &f"
                    + store.openIncidents() + " &7открытых жалоб, &f" + store.frozenCount() + " &7заморожено"
                    + (store.settings().decayEnabled ? ", &f" + store.decayedCount() + " &7заброшено (decay)" : "") + ".");
            case "who" -> who(sender);
            case "inspect" -> inspect(sender);
            case "setowner" -> setOwner(sender, args);
            case "clearowner" -> clearOwner(sender);
            case "diag" -> diag(sender);
            case "papi" -> papi(sender, args);
            case "info" -> {
                if (args.length < 2) { Msg.send(sender, pfx() + "&7/hv info <ник>"); return true; }
                UUID u = store.uuidByName(args[1]);
                if (u == null) { Msg.send(sender, pfx() + "&cИгрок не найден."); return true; }
                DataStore.PlayerData d = store.getPlayer(u);
                int ptMin = d == null ? 0 : d.playtimeMin;
                Msg.send(sender, pfx() + "&7Досье &f" + args[1] + "&7:");
                Msg.send(sender, " &8• статус: " + store.tag(u) + " &7(" + store.status(u) + ")");
                Msg.send(sender, " &8• rep: &f" + store.reputation(u) + "  &8жалоб: &f" + store.incidentCount(u));
                Msg.send(sender, " &8• playtime: &f" + (ptMin / 60) + "ч " + (ptMin % 60) + "м");
                Msg.send(sender, " &8• bypass: &f" + store.isBypass(u) + "  &8verified: &f" + store.isVerified(u)
                        + "  &8staff: &f" + store.isStaff(u) + "  &8frozen: &f" + store.isFrozen(u));
                int gs = store.gateState(u, store.isStaff(u));
                Msg.send(sender, " &8• опасные механики: &f" + (gs == 0 ? "открыты" : gs < 0 ? "ограничены (рейтинг)" : "через " + PlaytimeGateListener.fmt(gs)));
                Msg.send(sender, " &8• доверяет (выдал доступ): &f" + store.trustedOf(u).size() + " игрокам");
                if (store.settings().decayEnabled) {
                    DataStore.PlayerData pd = store.getPlayer(u);
                    long ll = pd == null ? 0 : pd.lastLoginTs;
                    String llStr = ll == 0 ? "неизвестно" : ((System.currentTimeMillis() - ll) / 86_400_000L) + "д назад";
                    long mins = store.decayMinutesRemaining(u, System.currentTimeMillis());
                    String decayStr = mins == -1 ? "не применяется (exempt/выкл)"
                            : mins == 0 ? "&cПОСТРОЙКИ РАЗБЛОКИРОВАНЫ" : "через " + fmtDaysHours(mins);
                    Msg.send(sender, " &8• последний вход: &f" + llStr + "&8, decay: &f" + decayStr);
                }
            }
            case "reports" -> {
                UUID u = args.length >= 2 ? store.uuidByName(args[1]) : null;
                if (args.length >= 2 && u == null) { Msg.send(sender, pfx() + "&cИгрок не найден."); return true; }
                List<String> list = store.listIncidents(u, 15);
                if (list.isEmpty()) { Msg.send(sender, pfx() + "&7Инцидентов нет."); return true; }
                Msg.send(sender, pfx() + "&7Последние инциденты:");
                for (String s : list) Msg.send(sender, " &8" + s);
            }
            case "resolve" -> {
                if (args.length < 2) { Msg.send(sender, pfx() + "&7/hv resolve <id>"); return true; }
                try {
                    int id = Integer.parseInt(args[1]);
                    Msg.send(sender, pfx() + (store.resolveIncident(id) ? "&aИнцидент #" + id + " закрыт." : "&cНе найден или уже закрыт."));
                } catch (NumberFormatException ex) { Msg.send(sender, pfx() + "&cID — число."); }
            }
            case "bypass" -> {
                // /hv bypass <ник> [on|off]  |  /hv bypass remove <ник>
                if (args.length >= 3 && args[1].equalsIgnoreCase("remove")) {
                    UUID u = store.uuidByName(args[2]);
                    if (u == null) { Msg.send(sender, pfx() + "&cИгрок не найден."); return true; }
                    store.setBypass(u, args[2], false);
                    Msg.send(sender, pfx() + "&eBypass снят с &f" + args[2] + ".");
                    return true;
                }
                if (args.length < 2) { Msg.send(sender, pfx() + "&7/hv bypass <ник> [on|off] | /hv bypass remove <ник>"); return true; }
                UUID u = store.uuidByName(args[1]);
                if (u == null) { Msg.send(sender, pfx() + "&cИгрок не найден."); return true; }
                boolean val = args.length >= 3 ? args[2].equalsIgnoreCase("on") : !store.isBypass(u);
                store.setBypass(u, args[1], val);
                Msg.send(sender, pfx() + "&aBypass для &f" + args[1] + "&a: " + (val ? "ВКЛ" : "ВЫКЛ")
                        + " &8(механический доступ; репутация/репорты работают).");
            }
            case "freeze" -> {
                if (args.length < 2) { Msg.send(sender, pfx() + "&7/hv freeze <ник>"); return true; }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) { Msg.send(sender, pfx() + "&cИгрок не в сети."); return true; }
                store.freeze(t.getUniqueId());
                t.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false));
                Msg.send(t, pfx() + "&cВы заморожены администрацией. Ожидайте решения.");
                Msg.send(sender, pfx() + "&aИгрок &f" + t.getName() + " &aзаморожен.");
            }
            case "unfreeze" -> {
                if (args.length < 2) { Msg.send(sender, pfx() + "&7/hv unfreeze <ник>"); return true; }
                Player t = Bukkit.getPlayerExact(args[1]);
                UUID u = t != null ? t.getUniqueId() : store.uuidByName(args[1]);
                if (u == null) { Msg.send(sender, pfx() + "&cИгрок не найден."); return true; }
                store.unfreeze(u);
                if (t != null) { t.removePotionEffect(PotionEffectType.BLINDNESS); Msg.send(t, pfx() + "&aВы разморожены."); }
                Msg.send(sender, pfx() + "&aИгрок &f" + args[1] + " &aразморожен.");
            }
            case "verify" -> {
                if (args.length < 2) { Msg.send(sender, pfx() + "&7/hv verify <ник>"); return true; }
                UUID u = store.uuidByName(args[1]);
                if (u == null) { Msg.send(sender, pfx() + "&cИгрок не найден."); return true; }
                store.setVerified(u, args[1], true);
                Msg.send(sender, pfx() + "&aВерификация выдана &f" + args[1] + " &8(доступ к опасным механикам без наигрыша).");
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t != null) Msg.send(t, pfx() + "&aВам выдан доступ к опасным механикам (верификация).");
            }
            case "unverify" -> {
                if (args.length < 2) { Msg.send(sender, pfx() + "&7/hv unverify <ник>"); return true; }
                UUID u = store.uuidByName(args[1]);
                if (u == null) { Msg.send(sender, pfx() + "&cИгрок не найден."); return true; }
                store.setVerified(u, args[1], false);
                Msg.send(sender, pfx() + "&eВерификация снята с &f" + args[1] + ".");
            }
            case "migrate-storage" -> migrateStorage(sender, args);
            case "scrubowners" -> scrubOwners(sender, args);
            case "kills" -> killsLog(sender, args);
            case "evidence" -> evidence(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void who(CommandSender sender) {
        if (!(sender instanceof Player p)) { Msg.send(sender, "&cТолько для игроков."); return; }
        Block b = p.getTargetBlockExact(6);
        if (b == null) { Msg.send(p, pfx() + "&7Наведись на блок (до 6 блоков)."); return; }
        UUID owner = store.getOwner(ru.haven.util.BlockKey.of(b));
        String where = b.getType() + " " + b.getX() + "," + b.getY() + "," + b.getZ();
        if (owner == null) { Msg.send(p, pfx() + "&7" + where + ": &fничей &8(природный/не отслеживается)"); return; }
        Msg.send(p, pfx() + "&7" + where + ": владелец &f" + nameOf(owner) + " " + store.tag(owner));
    }

    private void inspect(CommandSender sender) {
        if (!(sender instanceof Player p)) { Msg.send(sender, "&cТолько для игроков."); return; }
        boolean on = store.toggleInspect(p.getUniqueId());
        Msg.send(p, pfx() + "&7Инспектор: " + (on ? "&aВКЛ — кликай по блокам" : "&cВЫКЛ"));
    }

    private void setOwner(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { Msg.send(sender, "&cТолько для игроков."); return; }
        if (args.length < 2) { Msg.send(p, pfx() + "&7/hv setowner <ник> &8(наведись на блок)"); return; }
        UUID t = store.uuidByName(args[1]);
        if (t == null) { Msg.send(p, pfx() + "&cИгрок не найден."); return; }
        Block b = p.getTargetBlockExact(6);
        if (b == null) { Msg.send(p, pfx() + "&7Наведись на блок."); return; }
        store.setOwner(BlockKey.of(b), t);
        Msg.send(p, pfx() + "&aВладелец блока " + b.getX() + "," + b.getY() + "," + b.getZ() + " → &f" + args[1]);
    }

    private void clearOwner(CommandSender sender) {
        if (!(sender instanceof Player p)) { Msg.send(sender, "&cТолько для игроков."); return; }
        Block b = p.getTargetBlockExact(6);
        if (b == null) { Msg.send(p, pfx() + "&7Наведись на блок."); return; }
        BlockKey k = BlockKey.of(b);
        boolean had = store.getOwner(k) != null;
        store.removeOwner(k);
        Msg.send(p, pfx() + (had ? "&aВладелец снят с блока." : "&7Блок и так был ничей."));
    }

    private void scrubOwners(CommandSender sender, String[] args) {
        if (args.length < 2) {
            scrubUsage(sender);
            return;
        }

        String mode = args[1].toLowerCase(Locale.ROOT);
        boolean dryRun = hasFlag(args, "--dry-run");
        boolean loadChunks = hasFlag(args, "--load-chunks");
        ScrubResult result;
        String scope;

        switch (mode) {
            case "radius" -> {
                if (!(sender instanceof Player p)) {
                    Msg.send(sender, pfx() + "&cRadius-scrub доступен только игроку. Для консоли используй &f/hv scrubowners world <world> --dry-run");
                    return;
                }
                int radius = parseRadius(sender, args);
                if (radius < 0) return;
                Location center = p.getLocation();
                int cx = center.getBlockX();
                int cy = center.getBlockY();
                int cz = center.getBlockZ();
                long radiusSq = (long) radius * radius;
                result = scrubOwnersInWorld(p.getWorld(), key -> {
                    long dx = (long) key.x() - cx;
                    long dy = (long) key.y() - cy;
                    long dz = (long) key.z() - cz;
                    return dx * dx + dy * dy + dz * dz <= radiusSq;
                }, dryRun, loadChunks);
                scope = "radius " + radius + " around " + p.getWorld().getName() + " " + cx + "," + cy + "," + cz;
            }
            case "world" -> {
                World world = resolveScrubWorld(sender, args);
                if (world == null) return;
                result = scrubOwnersInWorld(world, key -> true, dryRun, loadChunks);
                scope = "world " + world.getName();
            }
            default -> {
                scrubUsage(sender);
                return;
            }
        }

        if (!dryRun && result.stale() > 0) store.drainToDb();
        Msg.send(sender, pfx() + (dryRun ? "&eDRY-RUN " : "&a")
                + "scrubowners &7" + scope
                + "&7: candidates=&f" + result.candidates
                + "&7, stale=&f" + result.stale()
                + "&7, removed=&f" + (dryRun ? 0 : result.stale())
                + "&8 (air=" + result.air + ", invalid=" + result.invalid + ", y=" + result.outOfBounds + ")"
                + (result.skippedUnloaded > 0 ? " &eunloaded-skip=&f" + result.skippedUnloaded : "")
                + (result.loadedChunks > 0 ? " &7loaded-chunks=&f" + result.loadedChunks : ""));
        if (dryRun && result.stale() > 0) {
            Msg.send(sender, pfx() + "&7Чтобы реально почистить, повтори команду без &f--dry-run&7.");
        }
    }

    private void scrubUsage(CommandSender sender) {
        Msg.send(sender, pfx() + "&7/hv scrubowners radius [1-" + MAX_SCRUB_RADIUS + "] [--dry-run] [--load-chunks]");
        Msg.send(sender, pfx() + "&7/hv scrubowners world [world] [--dry-run] [--load-chunks]");
    }

    private int parseRadius(CommandSender sender, String[] args) {
        int radius = DEFAULT_SCRUB_RADIUS;
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("--")) continue;
            try {
                radius = Integer.parseInt(args[i]);
                break;
            } catch (NumberFormatException ex) {
                Msg.send(sender, pfx() + "&cRadius должен быть числом: &f" + args[i]);
                return -1;
            }
        }
        if (radius < 1 || radius > MAX_SCRUB_RADIUS) {
            Msg.send(sender, pfx() + "&cRadius должен быть от 1 до " + MAX_SCRUB_RADIUS + ".");
            return -1;
        }
        return radius;
    }

    private World resolveScrubWorld(CommandSender sender, String[] args) {
        String worldName = null;
        for (int i = 2; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                worldName = args[i];
                break;
            }
        }
        if (worldName != null) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) Msg.send(sender, pfx() + "&cМир не найден: &f" + worldName);
            return world;
        }
        if (sender instanceof Player p) return p.getWorld();
        Msg.send(sender, pfx() + "&cИз консоли укажи мир: &f/hv scrubowners world <world> --dry-run");
        return null;
    }

    private boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase(flag)) return true;
        }
        return false;
    }

    private ScrubResult scrubOwnersInWorld(World world, java.util.function.Predicate<BlockKey> filter,
                                          boolean dryRun, boolean loadChunks) {
        ScrubResult result = new ScrubResult();
        Map<BlockKey, UUID> snapshot = store.blockOwnerSnapshot();
        Set<Long> loadedByCommand = new HashSet<>();

        for (BlockKey key : snapshot.keySet()) {
            if (!key.world().equals(world.getUID()) || !filter.test(key)) continue;
            result.candidates++;

            ScrubReason reason = scrubReason(world, key, loadChunks, loadedByCommand, result);
            if (reason == null) continue;
            switch (reason) {
                case AIR -> result.air++;
                case INVALID -> result.invalid++;
                case OUT_OF_BOUNDS -> result.outOfBounds++;
            }
            if (!dryRun) store.removeOwner(key);
        }
        return result;
    }

    private ScrubReason scrubReason(World world, BlockKey key, boolean loadChunks,
                                    Set<Long> loadedByCommand, ScrubResult result) {
        if (key.y() < world.getMinHeight() || key.y() >= world.getMaxHeight()) {
            return ScrubReason.OUT_OF_BOUNDS;
        }

        int chunkX = key.x() >> 4;
        int chunkZ = key.z() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            if (!loadChunks) {
                result.skippedUnloaded++;
                return null;
            }
            long chunkKey = chunkKey(chunkX, chunkZ);
            if (loadedByCommand.add(chunkKey)) {
                world.loadChunk(chunkX, chunkZ);
                result.loadedChunks++;
            }
        }

        Material type = world.getBlockAt(key.x(), key.y(), key.z()).getType();
        if (type.isAir()) return ScrubReason.AIR;
        return isInvalidOwnerType(type) ? ScrubReason.INVALID : null;
    }

    private boolean isInvalidOwnerType(Material type) {
        return type == Material.WATER
                || type == Material.LAVA
                || type == Material.FIRE
                || type == Material.SOUL_FIRE;
    }

    private long chunkKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private enum ScrubReason { AIR, INVALID, OUT_OF_BOUNDS }

    private static final class ScrubResult {
        int candidates;
        int air;
        int invalid;
        int outOfBounds;
        int skippedUnloaded;
        int loadedChunks;

        int stale() {
            return air + invalid + outOfBounds;
        }
    }

    private void diag(CommandSender sender) {
        Settings s = store.settings();
        boolean papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        boolean tab = Bukkit.getPluginManager().getPlugin("TAB") != null;
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        Msg.send(sender, pfx() + "&7=== Haven diag ===");
        Msg.send(sender, " &8версия: &f" + plugin.getDescription().getVersion());
        Msg.send(sender, " &8protect-blocks: &f" + s.protectBlocks + "&8, explosions-off: &f" + s.blockExplosionDamage
                + "&8, fire: &f" + s.preventFireSpread + "&8, liquids: &f" + s.protectFromLiquids + "&8, pistons: &f" + s.preventPistonMove
                + "&8, entities: &f" + s.protectEntities);
        Msg.send(sender, " &8блоков: &f" + store.ownedBlockCount() + "&8, игроков: &f" + store.trackedPlayers()
                + "&8, стафф: &f" + store.staffCount() + "&8, жалоб: &f" + store.openIncidents() + "&8, frozen: &f" + store.frozenCount());
        // Storage diag — тип/пул/очередь/латенси последнего flush.
        Storage db = store.storage();
        if (db != null) {
            Msg.send(sender, " &8storage: &f" + db.backendName() + " &8| pool: &f" + db.poolStats());
        }
        if (store.worker() != null) {
            var w = store.worker();
            String health = w.isDegraded() ? " &4[DEGRADED]"
                    : (w.isAccepting() ? " &a[OK]" : " &e[STOPPED]");
            Msg.send(sender, " &8writer:" + health + " &fqueue=" + w.queueSize()
                    + " &8done=&f" + w.completedFlushes() + " &8failed=&f" + w.failedFlushes()
                    + " &8dropped=&f" + w.droppedTasks() + " &8last-flush=&f" + w.lastFlushMillis() + "мс"
                    + (w.lastError() != null ? " &8last-err=&c" + w.lastError() : ""));
        }
        Msg.send(sender, " &8PlaceholderAPI: &f" + papi + "&8, TAB: &f" + tab + "&8, debug: &f" + store.isDebug() + "&8, память: &f" + usedMb + " МБ");
    }

    /**
     * /hpvp accept|complain &lt;inquiryId&gt; — обработчик кнопок из {@code Notify.pvpInquiry}.
     * Отдельная top-level команда без permission (доступна всем игрокам) — потому что её
     * вызывает жертва, которая не обязательно админ. Безопасность: внутри валидируем что
     * sender == inquiry.victim, чужие inquiry трогать нельзя.
     */
    private boolean pvpHandle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { Msg.send(sender, "&cТолько для игроков."); return true; }
        if (args.length < 2) { Msg.send(p, pfx() + "&7Использование внутреннее (кнопки в чате после PvP-смерти)."); return true; }
        String op = args[0].toLowerCase();
        int id;
        try { id = Integer.parseInt(args[1]); }
        catch (NumberFormatException ex) { Msg.send(p, pfx() + "&cНеверный id."); return true; }

        long windowMs = store.settings().pvpInquiryWindowSec * 1000L;
        ru.haven.core.PvpInquiry inq = store.pvpInquiries().take(id, windowMs);
        if (inq == null) {
            Msg.send(p, pfx() + "&7Этот вопрос уже закрыт или истёк (окно " + (windowMs / 60000) + " мин).");
            return true;
        }
        if (!inq.victim.equals(p.getUniqueId())) {
            // Положим обратно: вдруг настоящая жертва ещё не успела нажать.
            store.pvpInquiries().register(inq);
            Msg.send(p, pfx() + "&cЭта кнопка не для тебя.");
            return true;
        }

        switch (op) {
            case "accept" -> {
                Msg.send(p, pfx() + "&aПонятно — был согласованный бой. Рейтинг &f"
                        + inq.killerName + "&a не меняется.");
                // Сброс cooldown'a: если эти же двое подерутся ещё раз — сразу новая кнопка.
                store.clearPvpCooldown(inq.killer, inq.victim);
                if (inq.killRecordId > 0) store.storage().updateKillInquiryResult(inq.killRecordId, "ACCEPTED");
                store.debug(() -> "PVP inquiry #" + inq.id + " accepted by " + p.getName());
            }
            case "complain" -> {
                // Дневной лимит репортов (L1): на этот же случай. Inquiry возвращаем в очередь,
                // чтобы жертва не «потеряла» кнопку — нажмёт после сброса/завтра.
                if (!store.canReportToday(p.getUniqueId())) {
                    store.pvpInquiries().register(inq);
                    Msg.send(p, pfx() + "&cПревышен дневной лимит жалоб (&f"
                            + store.settings().maxReportsPerDay
                            + "&c). Если это реальный гриф — позови админа в чате/Discord.");
                    return true;
                }
                int incidentId = store.addIncident(inq.killer, p.getUniqueId(),
                        "PvP без согласия — убил " + inq.victimName);
                if (inq.killRecordId > 0) store.storage().updateKillInquiryResult(inq.killRecordId, "COMPLAINED");
                int newRep = store.reputation(inq.killer);
                Msg.send(p, pfx() + "&aЖалоба отправлена (инцидент #" + incidentId + "). Рейтинг &f"
                        + inq.killerName + "&a понижен (сейчас &f" + newRep + "&a).");
                if (store.settings().pvpNotifyKillerOnComplain) {
                    Player killer = Bukkit.getPlayer(inq.killer);
                    if (killer != null && killer.isOnline()) {
                        Msg.send(killer, pfx() + "&c⚠ На тебя поступила жалоба за убийство &f"
                                + inq.victimName + "&c — рейтинг понижен &8(сейчас: &f" + newRep + "&8).");
                    }
                }
                // Алерт онлайн-админам, если репутация совсем плохая (тот же критерий что и /report).
                if (store.settings().sanctionsEnabled && newRep <= store.settings().flagBelow) {
                    String alert = pfx() + "&4[КРИТ] &c[PvP-репорт #" + incidentId + "] &f"
                            + p.getName() + " &7→ &f" + inq.killerName;
                    for (Player staff : Bukkit.getOnlinePlayers()) {
                        if (staff.hasPermission("haven.admin")) Msg.send(staff, alert);
                    }
                }
                store.clearPvpCooldown(inq.killer, inq.victim);
                // L3 confirmation alert на случай уже накопленных независимых жалоб.
                maybeFireConfirmedAlert(inq.killer, inq.killerName);
                store.debug(() -> "PVP inquiry #" + inq.id + " complaint by " + p.getName()
                        + " → incident #" + incidentId);
            }
            default -> {
                // Положим обратно — игрок мог опечататься.
                store.pvpInquiries().register(inq);
                Msg.send(p, pfx() + "&7Использование внутреннее: /hpvp accept|complain <id>");
            }
        }
        return true;
    }

    /**
     * /hv kills [ник] [дней] — лог PvP-убийств.
     * <ul>
     *   <li>{@code /hv kills} — последние убийства за 3 дня (limit 20).</li>
     *   <li>{@code /hv kills <ник>} — убийства с участием игрока (как killer ИЛИ victim) за 3 дня.</li>
     *   <li>{@code /hv kills <ник> 7} — за 7 дней.</li>
     *   <li>{@code /hv kills 7} — все за 7 дней (без фильтра по нику).</li>
     * </ul>
     * Старые записи остаются в БД навсегда — это аудит.
     */
    private void killsLog(CommandSender sender, String[] args) {
        // Парсинг: args[0]=="kills"; далее опционально ник и/или число дней.
        UUID filterUuid = null;
        String filterName = null;
        int days = 3;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            try { days = Integer.parseInt(a); continue; }
            catch (NumberFormatException ignored) { /* не число → ник */ }
            filterUuid = store.uuidByName(a);
            filterName = a;
            if (filterUuid == null) {
                Msg.send(sender, pfx() + "&cИгрок &f" + a + "&c не найден (должен был хоть раз заходить).");
                return;
            }
        }
        if (days < 1) days = 1;
        if (days > 365) days = 365;
        final int limit = 30;
        long sinceTs = System.currentTimeMillis() - days * 86400000L;

        java.util.List<ru.haven.storage.Storage.KillRecord> rows = filterUuid == null
                ? store.storage().recentKills(sinceTs, limit)
                : store.storage().killsByPlayer(filterUuid, sinceTs, limit, true, true);

        if (rows.isEmpty()) {
            Msg.send(sender, pfx() + "&7Убийств не найдено за последние " + days + " дн."
                    + (filterName != null ? " (по &f" + filterName + "&7)" : ""));
            return;
        }
        Msg.send(sender, pfx() + "&7Убийства за &f" + days + "&7 дн. "
                + (filterName != null ? "&7(по &f" + filterName + "&7) " : "")
                + "&8(показано " + rows.size() + (rows.size() == limit ? "+" : "") + "):");
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm");
        long now = System.currentTimeMillis();
        for (var r : rows) {
            String when = ageHuman(now - r.ts) + " назад";
            String when2 = sdf.format(new java.util.Date(r.ts));
            String resultTag = switch (r.inquiryResult == null ? "" : r.inquiryResult) {
                case "ACCEPTED" -> "&a[бой]";
                case "COMPLAINED" -> "&c[ЖАЛОБА]";
                default -> "&7[нет ответа]";
            };
            Msg.send(sender, " &8#" + r.id + " &7" + when2 + " &8(" + when + ")  &f"
                    + r.killerName + " &7→ &f" + r.victimName
                    + "  &8" + r.world + " " + r.x + "," + r.y + "," + r.z + "  " + resultTag);
        }
    }

    /** Человекочитаемая разница времени: «3ч 12м», «45с», «2д 4ч». */
    private static String ageHuman(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "с";
        long m = s / 60;
        if (m < 60) return m + "м";
        long h = m / 60;
        if (h < 24) return h + "ч " + (m % 60) + "м";
        long d = h / 24;
        return d + "д " + (h % 24) + "ч";
    }

    /** /hv migrate-storage <sqlite|mysql>: переезд данных между бэкендами. */
    private void migrateStorage(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Msg.send(sender, pfx() + "&7/hv migrate-storage <sqlite|mysql> &8— перенос данных из текущего бэкенда в указанный.");
            Msg.send(sender, " &8Сейчас активный: &f" + (store.storage() != null ? store.storage().backendName() : "?"));
            Msg.send(sender, " &cПОСЛЕ миграции: смени &fstorage.type&c в config.yml и перезапусти сервер.");
            return;
        }
        String target = args[1].toLowerCase();
        Storage src = store.storage();
        if (src == null) { Msg.send(sender, pfx() + "&cТекущий storage не инициализирован."); return; }

        // Подготовим целевой storage. НЕ переключаем на лету — только переносим данные.
        Storage dst;
        try {
            switch (target) {
                case "mysql", "mariadb" -> dst = new MysqlStorage(store.settings(), plugin.getLogger());
                case "sqlite" -> dst = new SqliteStorage(new java.io.File(plugin.getDataFolder(), "haven-migrated.db"),
                        plugin.getLogger());
                default -> { Msg.send(sender, pfx() + "&cЦель должна быть: sqlite | mysql"); return; }
            }
        } catch (Exception ex) {
            Msg.send(sender, pfx() + "&cНе удалось открыть целевой storage: &f" + ex.getMessage());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "migrate-storage open dst failed", ex);
            return;
        }
        if (src == dst) { Msg.send(sender, pfx() + "&7Цель совпадает с источником."); return; }

        Msg.send(sender, pfx() + "&7Миграция запущена... (синхронно; для крупной БД может занять минуты)");
        try {
            StorageMigrator.Report r = StorageMigrator.migrate(src, dst, plugin.getLogger());
            Msg.send(sender, pfx() + "&aМиграция завершена: &f" + r);
            Msg.send(sender, pfx() + "&7Теперь смени &fstorage.type&7 в config.yml и перезапусти сервер.");
        } catch (Exception ex) {
            Msg.send(sender, pfx() + "&cМиграция упала: &f" + ex.getMessage());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "migrate-storage failed", ex);
        } finally {
            try { dst.close(); } catch (Exception ignored) {}
        }
    }

    /** /hv evidence <id> — показать CoreProtect-снимок, приложенный к инциденту. */
    private void evidence(CommandSender sender, String[] args) {
        if (args.length < 2) { Msg.send(sender, pfx() + "&7/hv evidence <id инцидента>"); return; }
        int id;
        try { id = Integer.parseInt(args[1]); }
        catch (NumberFormatException ex) { Msg.send(sender, pfx() + "&cID — число."); return; }
        String ev = store.storage().getIncidentEvidence(id);
        if (ev == null || ev.isEmpty()) {
            boolean cp = plugin.coreProtect() != null && plugin.coreProtect().isAvailable();
            Msg.send(sender, pfx() + "&7К инциденту #" + id + " нет доказательств "
                    + (cp ? "&8(возможно, культприт ничего не ломал рядом с точкой репорта)." : "&8(CoreProtect недоступен)."));
            return;
        }
        Msg.send(sender, pfx() + "&7Доказательства к инциденту #" + id + "&7:");
        for (String line : ev.split("\n")) Msg.send(sender, " &8" + line);
    }

    private void papi(CommandSender sender, String[] args) {
        UUID u;
        String name;
        if (args.length >= 2) {
            u = store.uuidByName(args[1]); name = args[1];
            if (u == null) { Msg.send(sender, pfx() + "&cИгрок не найден."); return; }
        } else if (sender instanceof Player p) {
            u = p.getUniqueId(); name = p.getName();
        } else { Msg.send(sender, pfx() + "&7/hv papi <ник>"); return; }
        String tag = store.tag(u);
        Msg.send(sender, pfx() + "&7Плейсхолдеры для &f" + name + "&7:");
        Msg.send(sender, " &8%haven_tag% &7→ &r" + (tag.isEmpty() ? "&8(пусто)" : tag));
        Msg.send(sender, " &8%haven_status% &7→ &f" + store.status(u));
        Msg.send(sender, " &8%haven_reputation% &7→ &f" + store.reputation(u));
    }

    private String nameOf(UUID u) {
        String n = store.nameOf(u);
        return n != null ? n : u.toString().substring(0, 8);
    }

    private void help(CommandSender s) {
        Msg.send(s, pfx() + "&7Админ-команды:");
        Msg.send(s, " &f/hv reload &8— перезагрузить конфиг");
        Msg.send(s, " &f/hv info <ник> &8— досье игрока");
        Msg.send(s, " &f/hv reports [ник] &8— инциденты");
        Msg.send(s, " &f/hv resolve <id> &8— закрыть инцидент");
        Msg.send(s, " &f/hv bypass <ник> [on|off] &8— механический доступ");
        Msg.send(s, " &f/hv verify|unverify <ник> &8— доступ к опасным механикам без наигрыша");
        Msg.send(s, " &f/hv freeze|unfreeze <ник> &8— изолятор");
        Msg.send(s, " &f/hv stats &8— сводка по системе");
        Msg.send(s, " &f/hv debug [on|off] &8— подробные логи в консоль");
        Msg.send(s, " &f/hv who &8— владелец блока, на который смотришь");
        Msg.send(s, " &f/hv inspect &8— режим клика по блокам");
        Msg.send(s, " &f/hv setowner <ник> &8/ &fclearowner &8— править владельца блока");
        Msg.send(s, " &f/hv diag &8— диагностика плагина");
        Msg.send(s, " &f/hv papi [ник] &8— значения плейсхолдеров");
        Msg.send(s, " &f/hv scrubowners radius|world [--dry-run] &8— чистка stale owner-записей");
        Msg.send(s, " &f/hv migrate-storage <sqlite|mysql> &8— перенос данных между бэкендами");
        Msg.send(s, " &f/hv kills [ник] [дней] &8— лог PvP-убийств (по умолчанию 3 дня, лимит 30)");
        Msg.send(s, " &f/hv evidence <id> &8— CoreProtect-снимок к инциденту");
    }

    // ---- tab ----
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("haven") && args.length == 1) {
            for (String s : new String[]{"help", "reload", "stats", "debug", "diag", "who", "inspect", "setowner", "clearowner", "papi", "info", "reports", "resolve", "bypass", "verify", "unverify", "freeze", "unfreeze", "scrubowners", "migrate-storage", "kills", "evidence"}) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
            return out;
        }
        if (command.getName().equalsIgnoreCase("haven") && args.length == 2 && args[0].equalsIgnoreCase("scrubowners")) {
            for (String s : new String[]{"radius", "world"}) {
                if (s.startsWith(args[1].toLowerCase())) out.add(s);
            }
            return out;
        }
        if (command.getName().equalsIgnoreCase("haven") && args.length >= 3 && args[0].equalsIgnoreCase("scrubowners")) {
            for (String s : new String[]{"--dry-run", "--load-chunks"}) {
                if (s.startsWith(args[args.length - 1].toLowerCase())) out.add(s);
            }
            return out;
        }
        if (command.getName().equalsIgnoreCase("haven") && args.length == 2 && args[0].equalsIgnoreCase("migrate-storage")) {
            for (String s : new String[]{"sqlite", "mysql", "mariadb"}) {
                if (s.startsWith(args[1].toLowerCase())) out.add(s);
            }
            return out;
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(last)) out.add(p.getName());
        }
        return out;
    }
}
