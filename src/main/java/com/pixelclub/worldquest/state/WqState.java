package com.pixelclub.worldquest.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Everything the quests have accumulated. Written whole to {@code config/pxcworldquest/state.json} after every
 * change (write-then-rename). Money is whole baht; points are integers; times are epoch milliseconds (UTC).
 */
public final class WqState {

    public int version = 1;
    public Map<String, QuestState> quests = new LinkedHashMap<>();

    public QuestState quest(String id) {
        return quests.computeIfAbsent(id, k -> new QuestState());
    }

    public static final class QuestState {
        /** ACTIVE → REACHED (every goal met, celebrated) → OPENED (admin opened / auto). */
        public String phase = Phase.ACTIVE;
        public long reachedAt;
        public long openedAt;
        public Map<String, Conqueror> conquerors = new LinkedHashMap<>();
        public long fundPoints;
        public long fundMoney;
        public long fundItemPoints;
        public Map<String, Long> itemCounts = new TreeMap<>();
        public Map<String, PlayerRec> players = new LinkedHashMap<>();
        public TreeSet<String> firedMilestones = new TreeSet<>();
        /** uuid → console commands still owed to a player who was offline when earned. */
        public Map<String, List<String>> pendingCommands = new LinkedHashMap<>();
        public Anchor anchor;

        public PlayerRec player(String uuid, String name) {
            PlayerRec r = players.computeIfAbsent(uuid, k -> new PlayerRec());
            if (name != null && !name.isBlank()) r.name = name;
            return r;
        }

        public void owe(String uuid, List<String> commands) {
            if (commands == null || commands.isEmpty()) return;
            pendingCommands.computeIfAbsent(uuid, k -> new ArrayList<>()).addAll(commands);
        }
    }

    public static final class Phase {
        public static final String ACTIVE = "ACTIVE";
        public static final String REACHED = "REACHED";
        public static final String OPENED = "OPENED";

        private Phase() {
        }
    }

    public static final class Conqueror {
        public String name;
        /** When the tower stamped the conquest if known (sync), else when the credit arrived. */
        public long at;
        /** command | sync | import | admin */
        public String source;
    }

    public static final class PlayerRec {
        public String name;
        /** Points this player put into the fund (money + items). */
        public long fundPoints;
        public long money;
        public long itemPoints;
        /** Index into the quest's sorted tier list of the highest tier already reached (announced); -1 = none. */
        public int tierGranted = -1;
        /**
         * Tier ids whose reward commands were run or queued. Separate from tierGranted so commands added to a tier
         * later still reach everyone who reached it earlier (backfilled at boot, reload and join).
         */
        public TreeSet<String> tiersRewarded = new TreeSet<>();
        public long firstAt;
        public long lastAt;
    }

    public static final class Anchor {
        public String dimension;
        public double x;
        public double y;
        public double z;

        public Anchor() {
        }

        public Anchor(String dimension, double x, double y, double z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
