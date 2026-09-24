package ac.grim.grimac.neural.dataset;

import ac.grim.grimac.neural.NeuralConfig;
import ac.grim.grimac.neural.risk.EvidenceSnapshot;
import ac.grim.grimac.neural.telemetry.CombatFrame;
import ac.grim.grimac.neural.telemetry.TelemetryRecord;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One disk worker, bounded admission + per-player queues. Never queues a task for each frame. */
public final class DatasetManager implements AutoCloseable {
    private static final int MAX_PENDING_SNAPSHOTS = 64;

    private final java.util.concurrent.atomic.AtomicInteger pendingSnapshots = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong writtenSnapshots = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong droppedSnapshots = new java.util.concurrent.atomic.AtomicLong();
    private final Path root;
    private final byte[] pseudonymKey;
    private final ScheduledExecutorService worker;
    private final Map<UUID, Handle> sessions = new ConcurrentHashMap<>();
    private final Consumer<String> errors;
    private volatile NeuralConfig config;
    private volatile boolean closed;
    private long totalBytes;

    public DatasetManager(Path root, NeuralConfig config, Consumer<String> errors) throws IOException {
        this.root = root;
        this.config = config;
        this.errors = errors;
        Files.createDirectories(root.resolve("raw"));
        Files.createDirectories(root.resolve("metadata"));
        Files.createDirectories(root.resolve("snapshots"));
        Path keyPath = root.resolve("pseudonym.key");
        if (!Files.exists(keyPath)) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            Files.write(keyPath, key, StandardOpenOption.CREATE_NEW);
        }
        pseudonymKey = Files.readAllBytes(keyPath);
        if (pseudonymKey.length != 32) throw new IOException("Invalid dataset pseudonym key");
        for (String directory : List.of("raw", "snapshots")) {
            try (var files = Files.walk(root.resolve(directory))) {
                var iterator = files.filter(Files::isRegularFile).iterator();
                while (iterator.hasNext()) totalBytes += Files.size(iterator.next());
            }
        }
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Aero-neural-dataset");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::drainAll, 50, 50, TimeUnit.MILLISECONDS);
    }

    public String pseudonym(UUID playerId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pseudonymKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(playerId.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot pseudonymize dataset identity", e);
        }
    }

    public synchronized CompletableFuture<DatasetSession> open(DatasetMetadata metadata) {
        if (closed || !config.recordingEnabled()) return CompletableFuture.failedFuture(new IllegalStateException("Collection disabled"));
        if (sessions.size() >= config.maxSessions()) return CompletableFuture.failedFuture(new IllegalStateException("Session limit reached"));
        DatasetSession session = new DatasetSession(metadata, config.queueCapacity());
        Handle handle = new Handle(session, config);
        if (sessions.putIfAbsent(metadata.sessionId(), handle) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Duplicate session ID"));
        }
        CompletableFuture<DatasetSession> result = new CompletableFuture<>();
        worker.execute(() -> {
            try {
                if (!session.accepting()) throw new IOException("Session cancelled during open");
                if (totalBytes >= handle.limits.maxTotalBytes()) throw new IOException("Dataset disk quota reached");
                handle.counting = new CountingOutput(Files.newOutputStream(handle.rawPath(), StandardOpenOption.CREATE_NEW));
                handle.writer = new BufferedWriter(new OutputStreamWriter(handle.counting, StandardCharsets.UTF_8), 32768);
                handle.metadata(false);
                result.complete(session);
            } catch (Exception e) {
                fail(handle, e);
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    public synchronized void update(NeuralConfig replacement) {
        config = replacement;
        // Existing sessions are closed on any reload so each session has one immutable configuration.
        for (Handle handle : sessions.values()) handle.session.close("CONFIG_RELOAD");
    }

    public List<DatasetSession> sessions() {
        List<DatasetSession> result = new ArrayList<>();
        for (Handle handle : sessions.values()) result.add(handle.session);
        return result;
    }

    /**
     * Queues one evidence snapshot onto the existing disk worker. Returns false when the queue is
     * already deep: dropping a review artefact is preferable to letting it delay session writes.
     */
    public boolean submitSnapshot(EvidenceSnapshot snapshot) {
        if (closed || snapshot == null) return false;
        int pending;
        do {
            pending = pendingSnapshots.get();
            if (pending >= MAX_PENDING_SNAPSHOTS) { droppedSnapshots.incrementAndGet(); return false; }
        } while (!pendingSnapshots.compareAndSet(pending, pending + 1));
        try {
            worker.execute(() -> {
                try {
                    Path destination = root.resolve("snapshots/" + snapshot.timestampMillis() + "-" + snapshot.eventId() + ".json");
                    StringWriter encoded = new StringWriter();
                    SnapshotJson.write(encoded, snapshot);
                    byte[] bytes = encoded.toString().getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > config.maxTotalBytes() - totalBytes) {
                        droppedSnapshots.incrementAndGet();
                        return;
                    }
                    Files.write(destination, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    totalBytes += bytes.length;
                    writtenSnapshots.incrementAndGet();
                } catch (Exception error) {
                    droppedSnapshots.incrementAndGet();
                    errors.accept("Evidence snapshot not stored: " + error.getMessage());
                } finally {
                    pendingSnapshots.decrementAndGet();
                }
            });
            return true;
        } catch (RejectedExecutionException shuttingDown) {
            pendingSnapshots.decrementAndGet();
            droppedSnapshots.incrementAndGet();
            return false;
        }
    }

    public long writtenSnapshots() { return writtenSnapshots.get(); }
    public long droppedSnapshots() { return droppedSnapshots.get(); }

    private void drainAll() {
        for (Handle handle : sessions.values()) {
            if (handle.writer == null) continue;
            try {
                DatasetSession session = handle.session;
                if (System.nanoTime() - session.metadata.startNanos() >= handle.limits.maxDurationSeconds() * 1_000_000_000L) {
                    session.close("DURATION_LIMIT");
                }
                TelemetryRecord record;
                int drained = 0;
                while (drained++ < 512 && (record = session.poll()) != null) {
                    DatasetJson.writeRecord(handle.writer, session.metadata, record);
                    session.written(record instanceof CombatFrame);
                }
                handle.writer.flush();
                totalBytes += handle.counting.count - handle.accounted;
                handle.accounted = handle.counting.count;
                if (handle.accounted >= handle.limits.maxSessionBytes() || totalBytes >= handle.limits.maxTotalBytes()) {
                    session.close("DISK_QUOTA");
                }
                if (session.readyToFinish()) {
                    handle.writer.close();
                    handle.metadata(true);
                    session.complete();
                    sessions.remove(session.metadata.sessionId());
                }
            } catch (Exception e) {
                fail(handle, e);
            }
        }
    }

    private void fail(Handle handle, Exception error) {
        handle.session.fail(error.getClass().getSimpleName() + ": " + error.getMessage());
        try { if (handle.writer != null) handle.writer.close(); } catch (IOException ignored) { }
        try { handle.metadata(false); } catch (IOException ignored) { }
        sessions.remove(handle.session.metadata.sessionId());
        handle.session.complete();
        errors.accept("Dataset " + handle.session.metadata.sessionId() + " failed: " + error.getMessage());
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Handle handle : sessions.values()) handle.session.close("PLUGIN_STOP");
        worker.execute(() -> {
            // At most 4096 queued records per admitted session; producers have been closed.
            for (int i = 0; i < 10 && !sessions.isEmpty(); i++) drainAll();
        });
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                errors.accept("Dataset shutdown deadline exceeded; incomplete metadata must be reviewed");
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private final class Handle {
        final DatasetSession session;
        final NeuralConfig limits;
        CountingOutput counting;
        BufferedWriter writer;
        long accounted;

        Handle(DatasetSession session, NeuralConfig limits) { this.session = session; this.limits = limits; }
        Path rawPath() { return root.resolve("raw/session-" + session.metadata.sessionId() + ".jsonl"); }

        void metadata(boolean complete) throws IOException {
            Path destination = root.resolve("metadata/session-" + session.metadata.sessionId() + ".json");
            Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
            try (Writer output = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                DatasetJson.writeMetadata(output, session, complete, System.nanoTime());
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static final class CountingOutput extends FilterOutputStream {
        long count;
        CountingOutput(OutputStream output) { super(output); }
        @Override public void write(int value) throws IOException { out.write(value); count++; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length); count += length;
        }
    }
}
