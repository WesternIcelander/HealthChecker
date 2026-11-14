package io.siggi.healthchecker.history;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Atomic file replacement: the content goes to a temp file in the same directory, which is
 * flushed to disk and then renamed over the target. A crash or error at any point leaves
 * either the old or the new file, never a partial one.
 *
 * <p>Temp files are named {@code .{name}.{random}.tmp}; one left behind by a crash is
 * removed by {@link #deleteTempFiles(Path)}.
 */
public final class AtomicFiles {
    private static final String TEMP_SUFFIX = ".tmp";

    private AtomicFiles() {
    }

    /** Writes the content of a file. */
    @FunctionalInterface
    public interface Content {
        void writeTo(OutputStream out) throws IOException;
    }

    /** Replaces {@code target} with {@code data}, creating parent directories as needed. */
    public static void write(Path target, byte[] data) throws IOException {
        write(target, out -> out.write(data));
    }

    /**
     * Replaces {@code target} with what {@code content} writes. If {@code content} throws,
     * the target is left unchanged and the temp file is removed.
     */
    public static void write(Path target, Content content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = dir.resolve("." + target.getFileName() + "." + UUID.randomUUID() + TEMP_SUFFIX);
        boolean done = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                OutputStream out = Channels.newOutputStream(channel);
                content.writeTo(out);
                out.flush();
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            done = true;
            forceDirectory(dir);
        } finally {
            if (!done) {
                Files.deleteIfExists(temp);
            }
        }
    }

    /** Whether {@code file} looks like a temp file written by {@link #write}. */
    public static boolean isTempFile(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith(".") && name.endsWith(TEMP_SUFFIX);
    }

    /** Deletes temp files left behind in {@code dir} by an interrupted write. */
    public static void deleteTempFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                if (isTempFile(file) && Files.isRegularFile(file)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /** Makes the rename durable where the platform supports syncing a directory. */
    private static void forceDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // Not supported on every platform (e.g. Windows); the rename itself is still atomic.
        }
    }
}
