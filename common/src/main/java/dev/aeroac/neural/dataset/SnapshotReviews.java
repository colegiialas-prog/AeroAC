package dev.aeroac.neural.dataset;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * A moderator's CHEAT or LEGIT verdict on one evidence snapshot, written next to the snapshots as
 * {@code reviews/<eventId>.json}.
 *
 * <p>It is a weak label and is kept apart on purpose: the snapshot file itself stays UNLABELED, and
 * the Python pipeline reads these only when training runs with {@code --include-staff-reviews},
 * and then only into the train fold (ml/aeroml/dataset/reviews.py). Disk work; call it off the
 * server thread.
 */
public final class SnapshotReviews {
    public enum Verdict { CHEAT, LEGIT }

    public record Review(Verdict verdict, String reviewer, long reviewedAtMillis) { }

    private SnapshotReviews() { }

    /** The reviews directory beside a snapshots directory. */
    public static Path directoryFor(Path snapshots) {
        return snapshots.resolveSibling("reviews");
    }

    public static void write(Path reviews, String eventId, String snapshotFile, Verdict verdict, String reviewer,
                             long nowMillis) throws IOException {
        Path target = path(reviews, eventId);
        if (snapshotFile == null || snapshotFile.contains("/") || snapshotFile.contains("\\") || snapshotFile.contains("..")) {
            throw new IllegalArgumentException("invalid snapshot file");
        }
        JsonObject json = new JsonObject();
        json.addProperty("kind", "snapshotReview");
        json.addProperty("eventId", eventId);
        json.addProperty("snapshotFile", snapshotFile);
        json.addProperty("verdict", verdict.name());
        json.addProperty("labelSource", "STAFF_REVIEWED");
        json.addProperty("reviewer", reviewer == null ? "?" : reviewer);
        json.addProperty("reviewedAt", nowMillis);
        Files.createDirectories(reviews);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The verdict on a snapshot, or null when nobody reviewed it (or the file is unreadable). */
    public static Review read(Path reviews, String eventId) {
        try {
            Path file = path(reviews, eventId);
            if (!Files.isRegularFile(file)) return null;
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return new Review(Verdict.valueOf(json.get("verdict").getAsString()),
                    json.get("reviewer").getAsString(), json.get("reviewedAt").getAsLong());
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    public static boolean clear(Path reviews, String eventId) throws IOException {
        return Files.deleteIfExists(path(reviews, eventId));
    }

    /** Only a real event id names a file, so nothing can be written outside the directory. */
    private static Path path(Path reviews, String eventId) {
        return reviews.resolve(UUID.fromString(eventId) + ".json");
    }
}
