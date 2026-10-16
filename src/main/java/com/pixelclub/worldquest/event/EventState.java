package com.pixelclub.worldquest.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code config/pxcworldquest/event-state.json}. Times are epoch ms (0 = not set). The phase moves one way:
 * IDLE → COLLECTING → (COUNTDOWN → ENDING → UNLOCKED | FAILED); an admin reset returns it to IDLE.
 * Invariant: {@code donated == Σ players[*].count} (checked at load and by {@code admin status}).
 */
public final class EventState {

    public enum Phase { IDLE, COLLECTING, COUNTDOWN, ENDING, UNLOCKED, FAILED }

    public String id = "";
    public Phase phase = Phase.IDLE;
    public long donated;
    /** Admin additions (admin add), counted in {@link #donated} but not in any player's total. */
    public long adminAdded;
    public long startedAt, deadlineAt, countdownEndsAt, endsStartedAt, unlockedAt, failedAt;
    public boolean unlockCommandsRun, failCommandsRun, slamShown;
    public List<Integer> marksDone = new ArrayList<>();
    public List<Integer> warnsDone = new ArrayList<>();
    public List<Integer> milestonesDone = new ArrayList<>();
    public Map<String, Contributor> players = new LinkedHashMap<>();

    public static final class Contributor {
        public String name;
        public long count;
        public long last;

        public Contributor() {
        }

        public Contributor(String name) {
            this.name = name;
        }
    }

    public long sumPlayers() {
        long s = 0;
        for (Contributor c : players.values()) s += c.count;
        return s;
    }

    public boolean invariantHolds() {
        return donated == sumPlayers() + adminAdded;
    }

    /** Accepting candy: before the bar is full, and during the countdown too (the crowd keeps feeding it). */
    public boolean accepting() {
        return phase == Phase.COLLECTING || phase == Phase.COUNTDOWN;
    }
}
