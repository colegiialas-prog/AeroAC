package dev.aeroac.manager.config;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Makes {@code plugins/AeroAC} the one active data folder, once, by copying what an installation
 * already has in {@code plugins/GrimAC}.
 *
 * <p>The rename happened in the build files, where {@code getDataFolder()} follows the plugin name,
 * but not in the hard drive of every server that upgraded: those installations still keep their
 * configuration, their recorded datasets and their model bundles under the old folder. Without this
 * step the plugin starts on a fresh folder, the bundled defaults answer every question with
 * "disabled", and an operator's own config is only discovered by wondering why their settings
 * vanished. The data folder is single: the old one is never read at runtime after migration, and it
 * is never modified at all.
 *
 * <p>Rules, in the order they are enforced:
 * <ul>
 *   <li><b>Copy only.</b> Nothing is ever moved, renamed or deleted — in either folder. A rollback is
 *       "put the old jar back", which is only true if the old folder still holds everything.</li>
 *   <li><b>One time.</b> A marker in the active folder ends the migration permanently; a second
 *       start is a no-op, even if somebody later recreates the old folder.</li>
 *   <li><b>Never overwrite the active folder.</b> A file that already exists there is compared, not
 *       replaced. Identical content is skipped; different content is a conflict.</li>
 *   <li><b>Fail closed.</b> Any conflict stops the whole migration before a single byte is written,
 *       and reports every conflicting path. Merging two different configurations automatically is
 *       how a server ends up with a config nobody wrote, so the plugin refuses and says so.</li>
 * </ul>
 *
 * <p>Only the entries that belong to a running installation are considered: the config files, the
 * recorded datasets (which carry the pseudonym key), the per-backend database files and the model
 * bundles. Logs, backups and anything else unknown are left where they are.
 */
public final class LegacyDataFolderMigration {

    /** The single folder the plugin reads and writes. */
    public static final String ACTIVE_FOLDER = "AeroAC";

    /** The folder older builds used. Read once, never written. */
    public static final String LEGACY_FOLDER = "GrimAC";

    /** Presence of this file in the active folder means the migration already ran. */
    public static final String MARKER_FILE = ".aeroac-migration.done";

    /** Single files carried over, relative to the data folder. */
    private static final List<String> FILES = List.of(
            "config.yml", "messages.yml", "discord.yml", "punishments.yml", "database.yml");

    /** Whole trees carried over, relative to the data folder. */
    private static final List<String> TREES = List.of("datasets", "databases", "bundles", "models");

    /** How much of the copied list is written into the marker, as evidence without bloat. */
    private static final int MARKER_DETAIL_LIMIT = 512;

    public enum Status {
        /** No old folder: a fresh installation, nothing to carry over. */
        NOTHING_TO_DO,
        /** The old folder was copied into the active one. */
        MIGRATED,
        /** The marker is present: this installation migrated on an earlier start. */
        ALREADY_MIGRATED,
        /** Both folders hold different content for the same path. Nothing was written. */
        CONFLICT,
        /** The copy itself failed. Whatever was copied is kept; no marker, so the next start retries. */
        FAILED
    }

    public record Result(Status status, Path activeFolder, Path legacyFolder, List<String> copied,
                         List<String> conflicts, String summary) {

        /** True when the installation may continue normally on the active folder. */
        public boolean ok() {
            return status == Status.NOTHING_TO_DO || status == Status.MIGRATED
                    || status == Status.ALREADY_MIGRATED;
        }

        public boolean migratedNow() {
            return status == Status.MIGRATED;
        }
    }

    private LegacyDataFolderMigration() { }

    /**
     * Runs the migration for a server whose plugin folder is {@code pluginsDirectory}.
     *
     * @param pluginsDirectory the directory holding one folder per plugin, normally {@code plugins}
     */
    public static Result migrate(Path pluginsDirectory) {
        Objects.requireNonNull(pluginsDirectory, "pluginsDirectory");
        Path active = pluginsDirectory.resolve(ACTIVE_FOLDER);
        Path legacy = pluginsDirectory.resolve(LEGACY_FOLDER);

        if (!Files.isDirectory(legacy)) {
            return new Result(Status.NOTHING_TO_DO, active, legacy, List.of(), List.of(),
                    "Папка " + active.toAbsolutePath() + " является активной; старой папки "
                            + legacy.toAbsolutePath() + " нет, переносить нечего.");
        }
        if (isSameFolder(active, legacy)) {
            return new Result(Status.FAILED, active, legacy, List.of(), List.of(),
                    "Активная и старая папки указывают на один и тот же каталог: "
                            + active.toAbsolutePath() + ". Перенос не выполнен.");
        }
        if (Files.exists(active.resolve(MARKER_FILE))) {
            return new Result(Status.ALREADY_MIGRATED, active, legacy, List.of(), List.of(),
                    "Перенос из " + legacy.toAbsolutePath() + " уже выполнялся ранее; "
                            + "активная папка " + active.toAbsolutePath() + " не изменялась.");
        }

        List<Path> sources;
        try {
            sources = sources(legacy);
        } catch (IOException unreadable) {
            return new Result(Status.FAILED, active, legacy, List.of(), List.of(),
                    "Не удалось прочитать старую папку " + legacy.toAbsolutePath() + ": "
                            + describe(unreadable));
        }
        if (sources.isEmpty()) {
            // An old folder with nothing we own: nothing to carry over, but the installation should
            // not be asked about it on every start either.
            return finish(active, legacy, List.of(), List.of(), Status.MIGRATED);
        }

        List<String> conflicts = new ArrayList<>();
        try {
            for (Path source : sources) {
                collectConflicts(legacy, active, source, conflicts);
            }
        } catch (IOException unreadable) {
            return new Result(Status.FAILED, active, legacy, List.of(), List.of(),
                    "Не удалось сравнить содержимое папок: " + describe(unreadable));
        }
        if (!conflicts.isEmpty()) {
            return new Result(Status.CONFLICT, active, legacy, List.of(), List.copyOf(conflicts),
                    "Перенос из " + legacy.toAbsolutePath() + " остановлен: в активной папке "
                            + active.toAbsolutePath() + " уже есть другие файлы для тех же путей ("
                            + conflicts.size() + "). Ничего не скопировано, активная папка не изменена. "
                            + "Устраните конфликты вручную, затем перезапустите сервер: "
                            + String.join(", ", conflicts));
        }

        List<String> copied = new ArrayList<>();
        try {
            for (Path source : sources) {
                copyTree(legacy, active, source, copied);
            }
        } catch (IOException failure) {
            return new Result(Status.FAILED, active, legacy, List.copyOf(copied), List.of(),
                    "Перенос из " + legacy.toAbsolutePath() + " прерван: " + describe(failure)
                            + ". Скопировано файлов: " + copied.size()
                            + ". Активная папка " + active.toAbsolutePath()
                            + " сохранена; перенос будет повторён при следующем запуске.");
        }
        return finish(active, legacy, copied, List.of(), Status.MIGRATED);
    }

    /** The active folder for a server, whether or not it exists yet. */
    public static Path activeFolder(Path pluginsDirectory) {
        return pluginsDirectory.resolve(ACTIVE_FOLDER);
    }

    /** The folder older builds used, whether or not it exists. */
    public static Path legacyFolder(Path pluginsDirectory) {
        return pluginsDirectory.resolve(LEGACY_FOLDER);
    }

    private static Result finish(Path active, Path legacy, List<String> copied, List<String> conflicts,
                                 Status status) {
        try {
            writeMarker(active, legacy, copied);
        } catch (IOException unwritable) {
            return new Result(Status.FAILED, active, legacy, List.copyOf(copied), conflicts,
                    "Файлы перенесены, но отметку о переносе записать не удалось ("
                            + describe(unwritable) + "). Удалите " + legacy.toAbsolutePath()
                            + " после проверки, иначе перенос повторится.");
        }
        return new Result(status, active, legacy, List.copyOf(copied), conflicts,
                copied.isEmpty()
                        ? "Активная папка " + active.toAbsolutePath() + " создана; переносить было нечего."
                        : "Перенесено файлов: " + copied.size() + " из " + legacy.toAbsolutePath()
                                + " в " + active.toAbsolutePath() + ". Старая папка не изменена; "
                                + "активной является " + active.toAbsolutePath() + ".");
    }

    /** The entries of the old folder this plugin owns, in a stable order. */
    private static List<Path> sources(Path legacy) throws IOException {
        List<Path> sources = new ArrayList<>();
        for (String file : FILES) {
            Path candidate = legacy.resolve(file);
            if (Files.isRegularFile(candidate)) sources.add(candidate);
        }
        for (String tree : TREES) {
            Path candidate = legacy.resolve(tree);
            if (Files.isDirectory(candidate)) sources.add(candidate);
        }
        return sources;
    }

    /** Walks a source entry and records every target path that exists with different content. */
    private static void collectConflicts(Path legacy, Path active, Path source, List<String> conflicts)
            throws IOException {
        if (Files.isDirectory(source)) {
            for (Path file : walk(source)) {
                compare(legacy, active, file, conflicts);
            }
            return;
        }
        compare(legacy, active, source, conflicts);
    }

    private static void compare(Path legacy, Path active, Path source, List<String> conflicts)
            throws IOException {
        Path target = active.resolve(legacy.relativize(source).toString());
        if (!Files.isRegularFile(target)) return;
        byte[] existing = Files.readAllBytes(target);
        byte[] incoming = Files.readAllBytes(source);
        if (!Arrays.equals(existing, incoming)) {
            conflicts.add(normalize(legacy.relativize(source)));
        }
    }

    /** Copies a source entry without ever replacing an existing target file. */
    private static void copyTree(Path legacy, Path active, Path source, List<String> copied)
            throws IOException {
        if (Files.isDirectory(source)) {
            for (Path file : walk(source)) {
                copyFile(legacy, active, file, copied);
            }
            return;
        }
        copyFile(legacy, active, source, copied);
    }

    private static void copyFile(Path legacy, Path active, Path source, List<String> copied)
            throws IOException {
        Path relative = legacy.relativize(source);
        Path target = active.resolve(relative.toString());
        if (Files.isRegularFile(target)) {
            // Identical files were filtered out as "already there" by the conflict scan; a file that
            // appeared in between is left alone rather than overwritten.
            byte[] existing = Files.readAllBytes(target);
            if (!Arrays.equals(existing, Files.readAllBytes(source))) {
                throw new FileAlreadyExistsException(target.toString());
            }
            return;
        }
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        try {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (FileAlreadyExistsException raced) {
            if (!Arrays.equals(Files.readAllBytes(target), Files.readAllBytes(source))) throw raced;
            return;
        }
        copied.add(normalize(relative));
    }

    /** Regular files below a directory, without following symbolic links out of the folder. */
    private static List<Path> walk(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) files.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Path::compareTo);
        return files;
    }

    private static void writeMarker(Path active, Path legacy, List<String> copied) throws IOException {
        Files.createDirectories(active);
        StringBuilder marker = new StringBuilder();
        marker.append("source=").append(legacy.toAbsolutePath()).append('\n');
        marker.append("active=").append(active.toAbsolutePath()).append('\n');
        marker.append("at=").append(Instant.now()).append('\n');
        marker.append("files=").append(copied.size()).append('\n');
        int shown = 0;
        for (String path : copied) {
            if (shown++ >= MARKER_DETAIL_LIMIT) {
                marker.append("...=").append(copied.size() - MARKER_DETAIL_LIMIT).append(" more\n");
                break;
            }
            marker.append("copied=").append(path).append('\n');
        }
        Files.writeString(active.resolve(MARKER_FILE), marker.toString(), StandardCharsets.UTF_8);
    }

    private static String normalize(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static boolean isSameFolder(Path active, Path legacy) {
        try {
            return Files.exists(active) && Files.isSameFile(active, legacy);
        } catch (IOException notComparable) {
            return active.toAbsolutePath().normalize().equals(legacy.toAbsolutePath().normalize());
        }
    }

    private static String describe(IOException error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    /** Used by diagnostics: the paths this migration looks at, without touching the disk. */
    public static List<String> ownedEntries() {
        List<String> entries = new ArrayList<>(FILES);
        entries.addAll(TREES);
        return List.copyOf(entries);
    }
}
