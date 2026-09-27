package dev.aeroac.neural.dataset;

import dev.aeroac.neural.NeuralConfig;
import dev.aeroac.neural.risk.EvidenceSnapshot;
import dev.aeroac.neural.telemetry.CombatFrame;
import dev.aeroac.neural.telemetry.TelemetryRecord;

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

    /** Where evidence snapshots are written; read by the snapshot viewer. */
    public Path snapshotsDirectory() { return root.resolve("snapshots"); }

    public String pseudonym(UUID playerId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pseudonymKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(playerId.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot pseudonymize dataset identity", e);
        }
    }

    /**
     * Admits a session and returns a future that completes only once the raw file, the metadata file
     * and the writer exist and the session is registered.
     *
     * <p>Registration happens first, synchronously, so the session limit and duplicate identifiers are
     * enforced before anything touches the disk; the future completes last, on the disk worker. An
     * acknowledgement that arrived before the destination existed would let a caller record into a
     * session whose data could never be written, so {@code ready} is set inside the same block that
     * creates the files and is what the completion marks.
     */
    public synchronized CompletableFuture<DatasetSession> open(DatasetMetadata metadata) {
        if (closed) return CompletableFuture.failedFuture(
                new IllegalStateException("Хранилище датасетов остановлено"));
        if (!config.recordingEnabled()) return CompletableFuture.failedFuture(
                new IllegalStateException("Запись выключена (neural.enabled и neural.collection.enabled)"));
        if (sessions.size() >= config.maxSessions()) return CompletableFuture.failedFuture(
                new IllegalStateException("Достигнут предел одновременных сессий (" + config.maxSessions() + ")"));
        DatasetSession session = new DatasetSession(metadata, config.queueCapacity());
        Handle handle = new Handle(session, config);
        if (sessions.putIfAbsent(metadata.sessionId(), handle) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Сессия с таким идентификатором уже открыта"));
        }
        CompletableFuture<DatasetSession> result = new CompletableFuture<>();
        worker.execute(() -> {
            try {
                if (!session.accepting()) throw new IOException("Сессия отменена до открытия файлов");
                if (totalBytes >= handle.limits.maxTotalBytes()) throw new IOException("Достигнут общий предел объёма датасетов");
                handle.counting = new CountingOutput(Files.newOutputStream(handle.rawPath(), StandardOpenOption.CREATE_NEW));
                handle.writer = new BufferedWriter(new OutputStreamWriter(handle.counting, StandardCharsets.UTF_8), 32768);
                handle.metadata(false);
                handle.ready = true;
                // Acknowledgement point: raw file + metadata file + writer + registration all exist.
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
        // Said out loud, because it is otherwise invisible: an operator who reloaded for an unrelated
        // reason has just ended somebody's recording, and the only trace would be a CONFIG_RELOAD in
        // a metadata file nobody opens. What was recorded up to this point is kept and closed
        // cleanly; what is lost is the rest of the session they were in the middle of taking.
        int open = 0;
        for (Handle handle : sessions.values()) {
            if (handle.session.accepting()) open++;
            handle.session.close("CONFIG_RELOAD");
        }
        if (open > 0) {
            // Not through the error channel: nothing failed here, and a caller that treats every
            // message on it as a fault would be told this recording broke when it did not.
            dev.aeroac.utils.anticheat.LogUtil.info("Перезагрузка конфигурации закрыла активных записей датасета: " + open
                    + ". Записанное сохранено с причиной CONFIG_RELOAD; продолжить сессию нельзя, "
                    + "нужно начать новую.");
        }
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
            if (!handle.ready || handle.writer == null) continue;
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

    /**
     * Stops recording and drains every admitted session to completion.
     *
     * <p>Two steps, in this order: close the sessions so no producer can add anything, then drain the
     * worker until the queues are empty, writing each session's final metadata ({@code complete=true})
     * before the executor is shut down. Waiting for the executor alone would be enough to stop, but
     * not to finish: a session whose last records were still queued would be left with metadata
     * claiming the recording was cut short. The drain is bounded by a deadline so a stuck disk cannot
     * hold server shutdown open.
     */
    public synchronized void close() {
        drainAndClose();
    }

    public synchronized void drainAndClose() {
        // Administrative/explicit shutdown: the caller is not the server tick, so it can wait.
        drainAndClose(5, TimeUnit.SECONDS);
    }

    /**
     * The same drain, with a caller-chosen budget for the wait.
     *
     * <p>Server shutdown calls this with a short budget on purpose. Blocking the server thread for
     * seconds is exactly the kind of stall this plugin must never cause, so on expiry the worker is
     * <em>not</em> interrupted — it owns its own internal deadline, it is a daemon, and interrupting
     * it would discard the records the drain exists to save. The wait is what is bounded, not the
     * data.
     *
     * @return true when the drain finished inside the budget
     */
    public synchronized boolean drainAndClose(long timeout, TimeUnit unit) {
        if (closed) return true;
        closed = true;
        for (Handle handle : sessions.values()) handle.session.close("PLUGIN_STOP");
        Path rootPath = root;
        worker.execute(() -> {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!sessions.isEmpty() && System.nanoTime() < deadline) drainAll();
        });
        worker.shutdown();
        try {
            if (worker.awaitTermination(timeout, unit)) return true;
            errors.accept("Очередь датасетов дописывается в фоне; неполные метаданные в "
                    + rootPath.toAbsolutePath() + " требуют проверки, если процесс завершится раньше");
            return false;
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** True once {@link #close()} ran: no new session can be admitted afterwards. */
    public boolean closed() {
        return closed;
    }

    private final class Handle {
        final DatasetSession session;
        final NeuralConfig limits;
        CountingOutput counting;
        BufferedWriter writer;
        long accounted;
        /** Set together with the writer and the metadata file: the session is ack-able only then. */
        volatile boolean ready;

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
