package ru.haven.core;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory реестр активных PvP-inquiry: «жертва, реши — был бой или нападение?».
 *
 * <p>Создаётся при {@code PlayerDeathEvent} (когда killer — игрок). Хранит до тех пор, пока
 * жертва не нажмёт одну из кнопок или пока не истечёт окно ({@code settings.pvpInquiryWindowSec}).
 * НЕ персистится: при рестарте сервера все «зависшие» вопросы пропадают (что разумно: жертва
 * скорее всего уже забыла, а через сутки разбирать стычки не имеет смысла).</p>
 */
public final class PvpInquiry {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    public final int id;
    public final UUID killer;
    public final UUID victim;
    public final String killerName;
    public final String victimName;
    public final long createdAtMillis;
    /** id записи из pvp_kills (для последующего UPDATE inquiry_result). -1 если killing не залогирован. */
    public int killRecordId = -1;

    public PvpInquiry(UUID killer, String killerName, UUID victim, String victimName) {
        this.id = SEQ.getAndIncrement();
        this.killer = Objects.requireNonNull(killer);
        this.victim = Objects.requireNonNull(victim);
        this.killerName = killerName;
        this.victimName = victimName;
        this.createdAtMillis = System.currentTimeMillis();
    }

    public boolean expired(long windowMillis) {
        return System.currentTimeMillis() - createdAtMillis > windowMillis;
    }

    /** Простой in-memory storage (TTL-cleanup лениво при доступе). */
    public static final class Registry {
        private final ConcurrentMap<Integer, PvpInquiry> byId = new ConcurrentHashMap<>();

        public PvpInquiry register(PvpInquiry inq) {
            byId.put(inq.id, inq);
            return inq;
        }

        /** Снять и вернуть (или null если нет/expired). */
        public PvpInquiry take(int id, long windowMillis) {
            PvpInquiry i = byId.remove(id);
            if (i == null) return null;
            if (i.expired(windowMillis)) return null;
            return i;
        }

        /** Прибраться: убрать все expired (опционально, для предотвращения roomy memory). */
        public int sweep(long windowMillis) {
            int removed = 0;
            for (var it = byId.entrySet().iterator(); it.hasNext();) {
                if (it.next().getValue().expired(windowMillis)) { it.remove(); removed++; }
            }
            return removed;
        }

        public int size() { return byId.size(); }
    }
}
