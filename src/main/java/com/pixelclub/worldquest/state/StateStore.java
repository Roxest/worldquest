package com.pixelclub.worldquest.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.pixelclub.worldquest.util.AtomicFiles;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Owns {@code state.json}. The JSON is serialised on the caller's thread (the server thread, so the snapshot is
 * consistent) and written on one IO thread in submission order. A file that does not parse is never overwritten:
 * the store goes {@link #broken()} and every write is refused until an admin fixes the file.
 */
public final class StateStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final Logger log;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pxcworldquest-state");
        t.setDaemon(true);
        return t;
    });
    private WqState state = new WqState();
    private String brokenReason;
    private volatile long lastWriteOkAt;
    private volatile String lastWriteError;

    public StateStore(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public void load() {
        brokenReason = null;
        if (!Files.exists(file)) {
            state = new WqState();
            log.info("[WorldQuest] no state.json yet — starting empty");
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            WqState s = GSON.fromJson(json, WqState.class);
            state = s == null ? new WqState() : s;
            if (state.quests == null) state.quests = new java.util.LinkedHashMap<>();
        } catch (IOException | JsonParseException e) {
            brokenReason = String.valueOf(e);
            log.error("[WorldQuest] state.json could not be read — the quest is FROZEN (no donation, no credit) "
                    + "and the file will not be overwritten until it is fixed: {}", brokenReason);
        }
    }

    public boolean broken() {
        return brokenReason != null;
    }

    public String brokenReason() {
        return brokenReason;
    }

    public WqState state() {
        return state;
    }

    /** Snapshot now, write on the IO thread. No-op (and logged once per call) while broken. */
    public void save() {
        if (broken()) {
            log.error("[WorldQuest] refused to write state.json: {}", brokenReason);
            return;
        }
        final String json = GSON.toJson(state);
        io.execute(() -> write(json));
    }

    private void write(String json) {
        try {
            AtomicFiles.writeString(file, json);
            lastWriteOkAt = System.currentTimeMillis();
            lastWriteError = null;
        } catch (IOException e) {
            lastWriteError = String.valueOf(e);
            log.error("[WorldQuest] writing state.json failed: {}", lastWriteError);
        }
    }

    public long lastWriteOkAt() {
        return lastWriteOkAt;
    }

    public String lastWriteError() {
        return lastWriteError;
    }

    /** Final synchronous flush at shutdown (after the last save() was queued). */
    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                log.error("[WorldQuest] state writer did not finish within 10s at shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
