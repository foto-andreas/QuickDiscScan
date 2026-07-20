package quickdiscscan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

final class NativeDiskMetrics {
    static final long OFFLINE = 1;
    static final long DIRECTORY = 1L << 1;
    static final long SYMBOLIC_LINK = 1L << 2;
    static final long REGULAR_FILE = 1L << 3;

    private static final boolean NATIVE_AVAILABLE = loadNativeLibrary();

    record Metadata(long logicalBytes, long physicalBytes, long flags, long device,
                    long fileIdentity, long linkCount) {
        boolean offline() {
            return (flags & OFFLINE) != 0;
        }

        boolean directory() {
            return (flags & DIRECTORY) != 0;
        }

        boolean symbolicLink() {
            return (flags & SYMBOLIC_LINK) != 0;
        }

        boolean regularFile() {
            return (flags & REGULAR_FILE) != 0;
        }
    }

    private NativeDiskMetrics() {}

    static Metadata read(Path path) throws IOException {
        if (NATIVE_AVAILABLE) {
            long[] values = read0(path.toAbsolutePath().toString());
            if (values != null && values.length == 6) {
                return new Metadata(values[0], values[1], values[2], values[3], values[4], values[5]);
            }
        }

        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        long flags = attributes.isDirectory() ? DIRECTORY
                : attributes.isSymbolicLink() ? SYMBOLIC_LINK
                : attributes.isRegularFile() ? REGULAR_FILE : 0;
        long logical = attributes.isRegularFile() ? attributes.size() : 0;
        return new Metadata(logical, logical, flags, 0, 0, 1);
    }

    static boolean nativeAvailable() {
        return NATIVE_AVAILABLE;
    }

    @SuppressWarnings("restricted")
    private static boolean loadNativeLibrary() {
        String mappedName = System.mapLibraryName("quickdiscscanmetrics");
        try {
            System.loadLibrary("quickdiscscanmetrics");
            return true;
        } catch (UnsatisfiedLinkError ignored) {
            // Packaged applications load the helper copied into their resources.
        }

        try (InputStream source = NativeDiskMetrics.class.getResourceAsStream(
                "/quickdiscscan/native/" + mappedName)) {
            if (source == null) {
                return false;
            }
            String suffix = mappedName.substring(mappedName.lastIndexOf('.'));
            Path library = Files.createTempFile("quickdiscscanmetrics-", suffix);
            Files.copy(source, library, StandardCopyOption.REPLACE_EXISTING);
            library.toFile().deleteOnExit();
            System.load(library.toAbsolutePath().toString());
            return true;
        } catch (IOException | UnsatisfiedLinkError | SecurityException ignored) {
            return false;
        }
    }

    private static native long[] read0(String path);
}
