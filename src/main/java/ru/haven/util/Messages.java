package ru.haven.util;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * i18n-слой: тексты, видимые игрокам, вынесены в {@code messages_<lang>.yml} (формат — <b>MiniMessage</b>:
 * градиенты {@code <gradient:#a:#b>}, hex {@code <#rrggbb>}, без legacy-скобок).
 *
 * <h3>Дизайн</h3>
 * <ul>
 *   <li>Язык — {@code language: ru|en} в config.yml.</li>
 *   <li>Файл распаковывается из jar при первом старте; недостающие ключи доливаются (как config.yml).</li>
 *   <li>Плейсхолдеры — {@code {name}}, варарг-парами {@code send(p,"key","name",v)}.</li>
 *   <li>Префикс — ключ {@code prefix} в самом messages-файле (свой MiniMessage-градиент; не путать с
 *       legacy-префиксом config.yml, который остаётся для админ/диагностики).</li>
 *   <li>Отсутствующий ключ → {@code <red>[missing: key]} (видно при разработке, не крашит).</li>
 * </ul>
 */
public final class Messages {

    private final Plugin plugin;
    private YamlConfiguration msgs = new YamlConfiguration();
    private String prefix = "";
    private String lang = "ru";

    public Messages(Plugin plugin) { this.plugin = plugin; }

    public String language() { return lang; }
    public String prefix() { return prefix; }

    /** Загрузить (или перезагрузить на /hv reload) файл языка. Префикс берётся из ключа {@code prefix}. */
    public void load(String language) {
        this.lang = (language == null || language.isBlank()) ? "ru" : language.toLowerCase();
        String resource = "messages_" + lang + ".yml";

        if (plugin.getResource(resource) == null) {
            plugin.getLogger().warning("messages: язык '" + lang + "' не найден в jar — откат на ru.");
            this.lang = "ru";
            resource = "messages_ru.yml";
        }

        File file = new File(plugin.getDataFolder(), resource);
        if (!file.exists()) {
            plugin.saveResource(resource, false);
        }
        this.msgs = YamlConfiguration.loadConfiguration(file);

        InputStream in = plugin.getResource(resource);
        if (in != null) {
            YamlConfiguration def = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));

            // Версионирование формата: если файл на диске старее формата в jar (например остался
            // legacy-&-формат после апгрейда на MiniMessage) — бэкапим и регенерируем из jar.
            // Иначе игрокам показались бы сырые &-коды / съеденные <теги>.
            int diskVer = msgs.getInt("config-version", 1);
            int jarVer = def.getInt("config-version", 1);
            if (diskVer < jarVer) {
                try {
                    File bak = new File(plugin.getDataFolder(), resource + ".v" + diskVer + ".bak");
                    java.nio.file.Files.move(file.toPath(), bak.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    plugin.saveResource(resource, false); // свежий из jar
                    this.msgs = YamlConfiguration.loadConfiguration(file);
                    plugin.getLogger().info("messages_" + lang + ".yml обновлён до формата v" + jarVer
                            + " (старый сохранён как " + bak.getName() + ").");
                } catch (Exception ex) {
                    plugin.getLogger().warning("messages: не удалось регенерировать устаревший файл: " + ex.getMessage());
                }
            } else {
                // Обычный авто-долив недостающих ключей (формат актуален).
                int added = 0;
                for (String key : def.getKeys(true)) {
                    if (def.isConfigurationSection(key)) continue;
                    if (!msgs.isSet(key)) { msgs.set(key, def.get(key)); added++; }
                }
                if (added > 0) {
                    try { msgs.save(file); } catch (Exception e) {
                        plugin.getLogger().warning("messages: не удалось сохранить долитые ключи: " + e.getMessage());
                    }
                    plugin.getLogger().info("messages_" + lang + ".yml: добавлено новых ключей — " + added + ".");
                }
            }
        }
        this.prefix = msgs.getString("prefix", "");
    }

    /** Прямая загрузка из готового конфига (для unit-тестов — без распаковки из jar). */
    public void loadDirect(YamlConfiguration cfg, String prefix) {
        this.msgs = cfg != null ? cfg : new YamlConfiguration();
        this.prefix = prefix == null ? "" : prefix;
    }

    /** Сырая строка по ключу (MiniMessage-синтаксис, без префикса). */
    public String raw(String key) {
        return msgs.getString(key, "<red>[missing: " + key + "]");
    }

    /**
     * Сырая строка (MiniMessage) с подставленными плейсхолдерами — БЕЗ рендера. Для тестов/спец-случаев.
     * @param ph пары ключ-значение: {@code format("trust.added","name","Steve")} → заменит {name}.
     */
    public String format(String key, Object... ph) {
        String s = raw(key);
        for (int i = 0; i + 1 < ph.length; i += 2) {
            s = s.replace("{" + ph[i] + "}", String.valueOf(ph[i + 1]));
        }
        return s;
    }

    /** Готовый Component (префикс + тело) — для welcome/спец-случаев. */
    public Component render(String key, Object... ph) {
        return Msg.mm(prefix + format(key, ph));
    }

    /** Отправить сообщение игроку/консоли с префиксом (MiniMessage). Пустое тело → не шлём. */
    public void send(CommandSender to, String key, Object... ph) {
        String body = format(key, ph);
        if (body == null || body.isEmpty()) return;
        Msg.mini(to, prefix + body);
    }

    /** Отправить «голую» MiniMessage-строку с префиксом (без ключа) — для динамических сообщений. */
    public void sendRaw(CommandSender to, String miniBody) {
        if (miniBody == null || miniBody.isEmpty()) return;
        Msg.mini(to, prefix + miniBody);
    }

    /** Список строк по ключу (многострочные сообщения, напр. welcome-гайд). */
    public List<String> list(String key) {
        return new ArrayList<>(msgs.getStringList(key));
    }

    /** Отправить многострочный блок (каждая строка — MiniMessage, БЕЗ префикса — это цельная панель). */
    public void sendList(CommandSender to, String key) {
        for (String line : list(key)) Msg.mini(to, line);
    }
}
