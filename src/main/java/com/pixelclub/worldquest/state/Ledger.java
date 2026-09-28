package com.pixelclub.worldquest.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Append-only audit trail, one JSON object per line in {@code ledger/YYYY-MM.jsonl} (UTC months). Every money
 * donation, item offering, credit and admin action lands here so the sink can be measured (reward-economy rule:
 * persist the counter you will audit) — the same line is echoed to the server log as {@code [WorldQuest] LEDGER}.
 */
public final class Ledger {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    private final Path dir;
    private final Logger log;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pxcworldquest-ledger");
        t.setDaemon(true);
        return t;
    });

    public Ledger(Path dir, Logger log) {
        this.dir = dir;
        this.log = log;
    }

    /** kind + ordered fields; "ts" (ISO UTC) is prepended. */
    public void record(String kind, Map<String, Object> fields) {
        long now = System.currentTimeMillis();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", Instant.ofEpochMilli(now).toString());
        row.put("kind", kind);
        row.putAll(fields);
        String line = GSON.toJson(row);
        log.info("[WorldQuest] LEDGER {}", line);
        Path file = dir.resolve(MONTH.format(Instant.ofEpochMilli(now)) + ".jsonl");
        io.execute(() -> {
            try {
                Files.createDirectories(dir);
                Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.error("[WorldQuest] ledger append failed ({}): {}", file, e.toString());
            }
        });
    }

    public void close() {
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
