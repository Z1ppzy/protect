package ru.haven.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Опциональная отправка алертов {@code [ПОДТВЕРЖДЕНО]} в Discord-канал через webhook.
 *
 * <h3>Дизайн</h3>
 * <ul>
 *   <li><b>Zero-deps:</b> JDK {@code java.net.http.HttpClient} + ручная JSON-сборка
 *       (одно поле {@code content} + {@code allowed_mentions}). Без Gson / Jackson.</li>
 *   <li><b>Async + fail-soft:</b> {@link HttpClient#sendAsync} с 5-секундным таймаутом;
 *       любая ошибка (DNS, timeout, HTTP 4xx/5xx) → {@code logger.warning}, плагин не падает.</li>
 *   <li><b>Безопасность mentions:</b> {@code allowed_mentions.parse=[]} гарантирует, что
 *       {@code @everyone} / {@code @here} / {@code <@user>} в тексте никогда не пингуют
 *       (даже если игрок назвался {@code @everyone}). Пингуется только указанная роль
 *       через {@code allowed_mentions.roles=[...]}.</li>
 *   <li><b>Опциональность:</b> если {@code webhook-url} пуст → метод тихо возвращается.</li>
 * </ul>
 *
 * <p>Точка вызова — {@code HavenCommand.maybeFireConfirmedAlert} (L3 anti-report-spam).
 */
public final class DiscordNotifier {
    private DiscordNotifier() {}

    /** Singleton HttpClient — переиспользует TCP-соединения и executor pool между вызовами. */
    private static volatile HttpClient HTTP_CLIENT;

    /** Текстовая «версия» в User-Agent — чтобы было видно в Discord audit log какой плагин шлёт. */
    private static final String USER_AGENT = "Haven-Plugin (https://github.com/Z1ppzy/Haven-)";

    /**
     * Отправить алерт {@code [ПОДТВЕРЖДЕНО]} в Discord. {@code webhookUrl} пустой/null —
     * фича выключена, метод тихо возвращается.
     */
    public static void sendConfirmedAlert(String culpritName,
                                          int distinctReporters,
                                          int windowHours,
                                          String webhookUrl,
                                          String roleId,
                                          Logger logger) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;
        // Backticks — внутрь Discord-кода (markdown). Имена в MC только [a-zA-Z0-9_], backtick не пройдёт,
        // но parens / unicode из displayname могут — escapeJson их безопасно прокинет в строку.
        String content = String.format(
                "🚨 **ПОДТВЕРЖДЕНО**: `%s` получил жалобы от **%d** разных репортёров за %dч. " +
                        "Стоит проверить — `/hv info %s`",
                culpritName, distinctReporters, windowHours, culpritName);
        String payload = buildPayload(content, roleId);
        postAsync(webhookUrl, payload, logger);
    }

    /**
     * Собрать JSON-payload для Discord-вебхука. Public для unit-тестов (см. HavenLogicTest).
     *
     * @param content   plain текст (с маркдауном Discord); может содержать любые символы.
     * @param roleId    snowflake-id роли для пинга, или null/"" — без пинга.
     */
    public static String buildPayload(String content, String roleId) {
        boolean hasRole = roleId != null && !roleId.isEmpty();
        String finalContent = hasRole ? ("<@&" + roleId + "> " + content) : content;

        StringBuilder json = new StringBuilder(256);
        json.append("{\"content\":\"").append(escapeJson(finalContent)).append("\",");
        // allowed_mentions: жёсткая защита от @everyone-абьюза через ник игрока.
        // parse:[] — Discord НЕ пытается парсить mentions в content; пингуется только то,
        // что явно перечислено ниже.
        json.append("\"allowed_mentions\":{\"parse\":[]");
        if (hasRole) {
            json.append(",\"roles\":[\"").append(escapeJson(roleId)).append("\"]");
        }
        json.append("}}");
        return json.toString();
    }

    /** JSON-escape — минимальный, без зависимостей. Public для тестов. */
    public static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    private static void postAsync(String webhookUrl, String payload, Logger logger) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException ex) {
            // Битый URL в конфиге — лог один раз, не валим вызывающего.
            if (logger != null) logger.warning("Haven Discord: некорректный webhook-url: " + ex.getMessage());
            return;
        }
        httpClient().sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .whenComplete((resp, err) -> {
                    if (err != null) {
                        if (logger != null) logger.log(Level.WARNING,
                                "Haven Discord webhook не доставлен: " + err.getMessage());
                    } else if (resp != null && resp.statusCode() >= 400) {
                        if (logger != null) logger.warning(
                                "Haven Discord webhook HTTP " + resp.statusCode()
                                        + " — проверь правильность URL/прав вебхука.");
                    }
                });
    }

    /** Lazy single-instance HttpClient (DCL для перформанса; futures-pool управляется самим клиентом). */
    private static HttpClient httpClient() {
        HttpClient c = HTTP_CLIENT;
        if (c == null) {
            synchronized (DiscordNotifier.class) {
                if (HTTP_CLIENT == null) {
                    HTTP_CLIENT = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                }
                c = HTTP_CLIENT;
            }
        }
        return c;
    }
}
