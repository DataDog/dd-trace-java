package datadog.trace.civisibility.coverage.probe;

import datadog.trace.api.Config;
import datadog.trace.api.internal.VisibleForTesting;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classes with line probes inserted, kept in a directory that the JVMs of a build share, so that a
 * class that several JVMs load is patched once. The cache only saves work: an entry that cannot be
 * read, fails its integrity check or was written for other bytes is a miss, and the class is then
 * patched in the JVM as it is without the cache.
 *
 * <p>An entry is found by the CRC32 and length of the class's original bytes, holds a copy of them
 * and is used only if they are equal. It holds the patched class without its class id, which the
 * patched code loads from a constant of its own: a hit writes into that constant the id the class
 * gets in this JVM. An entry is written to a temporary file that is then moved into place
 * atomically, and it ends with a CRC32 of the rest.
 *
 * <p>Entries are kept per agent build, in a directory named after the CRC32 and length of the agent
 * jar's central directory, which lists the CRC32 and size of every file in the jar. That directory,
 * and the directories and files in it, are created for the user alone, and the cache is not used if
 * anyone else can reach the directory.
 */
public final class ProbeClassCache {

  private static final Logger log = LoggerFactory.getLogger(ProbeClassCache.class);

  private static final String BUILD_DIRECTORY_PREFIX = "dd-line-probes-";

  /**
   * A build directory this old was left by a build that did not end, and the next build deletes it.
   */
  private static final long STALE_BUILD_DIRECTORY_MILLIS = TimeUnit.HOURS.toMillis(24);

  private static final int MAGIC = 0x44444C50;
  private static final int FORMAT = 1;

  /** A longer entry, or central directory, is taken to be damaged. */
  private static final int MAX_LENGTH = 64 << 20;

  /** The end record of a ZIP file: its signature and length, and the comment that may follow it. */
  private static final int END_RECORD_SIGNATURE = 0x06054B50;

  private static final int END_RECORD_LENGTH = 22;
  private static final int MAX_COMMENT_LENGTH = 0xFFFF;

  /** Where the end record holds the size of the central directory, and where it starts. */
  private static final int CENTRAL_DIRECTORY_SIZE_AT = 12;

  private static final int CENTRAL_DIRECTORY_OFFSET_AT = 16;

  /** Counted for an entry that fails a check. */
  private static final String DAMAGED = "cache: damaged";

  /** Counted for a class that is not written to the cache. */
  private static final String NOT_WRITTEN = "cache: not written";

  /** What a cache root may allow: anything but writing by other users. */
  private static final Set<PosixFilePermission> ROOT_PERMISSIONS =
      EnumSet.complementOf(
          EnumSet.of(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE));

  /** What a directory of the cache may allow: anything, to its owner only. */
  private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
      EnumSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE);

  private static final FileAttribute<Set<PosixFilePermission>> PRIVATE_DIRECTORY =
      PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS);

  private static final FileAttribute<Set<PosixFilePermission>> PRIVATE_FILE =
      PosixFilePermissions.asFileAttribute(
          EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

  /** Writing stops after this many failures, such as those of a read-only or full directory. */
  static final int MAX_WRITE_FAILURES = 10;

  private final File directory;
  private final String buildId;
  private final AtomicInteger writeFailures = new AtomicInteger();

  private ProbeClassCache(File directory, String buildId) {
    this.directory = directory;
    this.buildId = buildId;
  }

  /**
   * The cache in the directory that the configuration names, or {@code null} if it names none or
   * the directory cannot be used, which is logged at warn level.
   */
  @Nullable
  public static ProbeClassCache open(Config config) {
    String root = config.getCiVisibilityCoverageLineProbesClassCacheDir();
    if (root == null || root.isEmpty()) {
      return null;
    }
    File agentJar;
    try {
      agentJar = config.getCiVisibilityAgentJarFile();
    } catch (IllegalArgumentException e) {
      notUsed(root, "the agent jar is not known");
      return null;
    }
    return open(new File(root), agentJar);
  }

  @Nullable
  static ProbeClassCache open(File root, File agentJar) {
    String problem = checkDirectory(root);
    if (problem != null) {
      notUsed(root.getPath(), problem);
      return null;
    }
    String buildId = buildId(agentJar);
    if (buildId == null) {
      notUsed(root.getPath(), "the agent jar " + agentJar + " could not be read");
      return null;
    }
    Path directory = root.toPath().resolve(buildId);
    try {
      createPrivateDirectory(directory);
      problem = checkBuildDirectory(directory);
    } catch (IOException | RuntimeException e) {
      problem = "the directory for this agent build could not be created: " + e;
    }
    if (problem != null) {
      notUsed(directory.toString(), problem);
      return null;
    }
    return new ProbeClassCache(directory.toFile(), buildId);
  }

  private static void notUsed(String root, String reason) {
    PatcherStats.count("cache: not used");
    log.warn(
        "Per-test line coverage does not use the class cache in {}, because {}, so each test JVM"
            + " inserts its own line probes.",
        root,
        reason);
  }

  /**
   * Why a directory cannot hold a cache, or {@code null} if it can. On a file system with POSIX
   * permissions it must belong to the user and be writable by no one else, so that no other user
   * can place classes in it.
   */
  @Nullable
  static String checkDirectory(File root) {
    if (!root.isDirectory()) {
      return "it is not a directory";
    }
    return checkPermissions(
        root.toPath(), ROOT_PERMISSIONS, "users other than its owner can write to it");
  }

  /**
   * Why the directory of an agent build in a cache root cannot hold its entries, or {@code null} if
   * it can. On a file system with POSIX permissions it must belong to the user, not be a link, and
   * allow no one else anything, so that no other user can reach the entries in it.
   */
  @Nullable
  static String checkBuildDirectory(Path directory) {
    return checkPermissions(
        directory,
        PRIVATE_DIRECTORY_PERMISSIONS,
        "users other than its owner can use it",
        LinkOption.NOFOLLOW_LINKS);
  }

  /**
   * Why a directory does not belong to the user or allows more than {@code allowed}, or {@code
   * null} if neither. A file system without POSIX permissions, such as on Windows, is not checked.
   */
  @Nullable
  private static String checkPermissions(
      Path directory, Set<PosixFilePermission> allowed, String tooOpen, LinkOption... linkOptions) {
    PosixFileAttributes attributes;
    try {
      attributes = Files.readAttributes(directory, PosixFileAttributes.class, linkOptions);
    } catch (UnsupportedOperationException e) {
      return null;
    } catch (IOException | RuntimeException e) {
      return "its owner could not be read: " + e;
    }
    if (attributes.isSymbolicLink()) {
      return "it is a link";
    }
    if (!attributes.isDirectory()) {
      return "it is not a directory";
    }
    String owner = attributes.owner().getName();
    String user = System.getProperty("user.name");
    if (!owner.equals(user)) {
      return "it belongs to " + owner + ", not to " + user;
    }
    if (!allowed.containsAll(attributes.permissions())) {
      return tooOpen;
    }
    return null;
  }

  /**
   * Creates a directory that only its owner can use, unless there is a directory there already. On
   * a file system without POSIX permissions it gets the default permissions.
   */
  private static void createPrivateDirectory(Path directory) throws IOException {
    if (Files.isDirectory(directory)) {
      return;
    }
    try {
      try {
        Files.createDirectory(directory, PRIVATE_DIRECTORY);
      } catch (UnsupportedOperationException e) {
        Files.createDirectory(directory);
      }
    } catch (FileAlreadyExistsException e) {
      // created at the same time by another JVM
      if (!Files.isDirectory(directory)) {
        throw e;
      }
    }
  }

  /**
   * Creates a new file with a unique name that only its owner can read and write. On a file system
   * without POSIX permissions it gets the default permissions.
   */
  private static Path createPrivateTemporaryFile(Path directory, String prefix) throws IOException {
    try {
      return Files.createTempFile(directory, prefix, ".tmp", PRIVATE_FILE);
    } catch (UnsupportedOperationException e) {
      return Files.createTempFile(directory, prefix, ".tmp");
    }
  }

  /**
   * The id of the agent build: the CRC32 and length of the jar's central directory, or {@code null}
   * if it cannot be read. The central directory lists the CRC32 and size of every file in the jar.
   */
  @Nullable
  static String buildId(File jar) {
    try (RandomAccessFile in = new RandomAccessFile(jar, "r")) {
      long length = in.length();
      int tailLength = (int) Math.min(length, END_RECORD_LENGTH + MAX_COMMENT_LENGTH);
      byte[] tail = new byte[tailLength];
      in.seek(length - tailLength);
      in.readFully(tail);
      for (int at = tailLength - END_RECORD_LENGTH; at >= 0; at--) {
        if (littleEndianInt(tail, at) != END_RECORD_SIGNATURE) {
          continue;
        }
        long size = littleEndianInt(tail, at + CENTRAL_DIRECTORY_SIZE_AT) & 0xFFFFFFFFL;
        long offset = littleEndianInt(tail, at + CENTRAL_DIRECTORY_OFFSET_AT) & 0xFFFFFFFFL;
        if (offset + size != length - tailLength + at || size > MAX_LENGTH) {
          return null; // a ZIP64 jar, or one with data before its end record
        }
        byte[] centralDirectory = new byte[(int) size];
        in.seek(offset);
        in.readFully(centralDirectory);
        CRC32 crc = new CRC32();
        crc.update(centralDirectory, 0, centralDirectory.length);
        return "v" + FORMAT + '-' + hex(crc.getValue()) + '-' + Long.toHexString(size);
      }
      return null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /** The file of the entry for a class's original bytes. */
  File entryFile(byte[] source) {
    CRC32 crc = new CRC32();
    crc.update(source, 0, source.length);
    String name = hex(crc.getValue()) + '-' + Integer.toHexString(source.length);
    return new File(new File(directory, name.substring(0, 2)), name);
  }

  /** The entry for a class's original bytes, or {@code null} if there is none that can be used. */
  @Nullable
  Entry read(File file, byte[] source) {
    if (!file.isFile()) {
      return null; // cheaper than the exception that opening a missing file throws
    }
    byte[] content;
    try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
      long length = in.length();
      if (length < 4 || length > MAX_LENGTH) {
        PatcherStats.count(DAMAGED);
        return null;
      }
      content = new byte[(int) length];
      in.readFully(content);
    } catch (FileNotFoundException e) {
      return null;
    } catch (IOException | RuntimeException e) {
      PatcherStats.count("cache: read failed");
      return null;
    }
    int end = content.length - 4;
    CRC32 crc = new CRC32();
    crc.update(content, 0, end);
    if ((int) crc.getValue() != bigEndianInt(content, end)) {
      PatcherStats.count(DAMAGED);
      return null;
    }
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(content, 0, end));
      if (in.readInt() != MAGIC || in.readInt() != FORMAT || !buildId.equals(in.readUTF())) {
        PatcherStats.count(DAMAGED);
        return null;
      }
      byte[] cachedSource = new byte[readLength(in, 1)];
      in.readFully(cachedSource);
      if (!Arrays.equals(cachedSource, source)) {
        // another class with the same CRC32 and length
        PatcherStats.count("cache: other bytes");
        return null;
      }
      String className = in.readUTF();
      String[] skippedMethods = new String[readLength(in, 2)];
      for (int k = 0; k < skippedMethods.length; k++) {
        skippedMethods[k] = in.readUTF();
      }
      Entry entry;
      if (in.readBoolean()) {
        String sourceFile = in.readUTF();
        String sourceDebug = in.readBoolean() ? readChars(in) : null;
        int classIdSlot = in.readInt();
        byte[] bytes = new byte[readLength(in, 1)];
        in.readFully(bytes);
        if (classIdSlot < 0 || classIdSlot > bytes.length - 4) {
          throw new IOException("class id slot out of range: " + classIdSlot);
        }
        ClassProbesBuilder probes = ClassProbesBuilder.readFrom(in);
        entry =
            new Entry(
                className, skippedMethods, bytes, sourceFile, sourceDebug, classIdSlot, probes);
      } else {
        entry = new Entry(className, skippedMethods, null, null, null, -1, null);
      }
      if (in.available() != 0) {
        throw new IOException("bytes after the entry");
      }
      return entry;
    } catch (IOException | RuntimeException e) {
      PatcherStats.count(DAMAGED);
      return null;
    }
  }

  /**
   * Writes the entry for a class's original bytes. Failures are counted, and writing stops after
   * {@link #MAX_WRITE_FAILURES}.
   *
   * @param instrumented the patched class, or {@code null} if it has no lines to cover
   */
  void write(
      File file,
      byte[] source,
      String className,
      Collection<String> skippedMethods,
      @Nullable ProbeClassTransformer.Instrumented instrumented) {
    if (writeFailures.get() >= MAX_WRITE_FAILURES) {
      return;
    }
    if (instrumented != null && !hasClassIdConstant(instrumented)) {
      // its class id would reach other JVMs
      PatcherStats.count(NOT_WRITTEN);
      return;
    }
    byte[] content;
    try {
      content = entryContent(source, className, skippedMethods, instrumented);
    } catch (IOException e) {
      // a name too long for writeUTF
      PatcherStats.count(NOT_WRITTEN);
      return;
    }
    Path parent = file.getParentFile().toPath();
    Path temporary = null;
    try {
      createPrivateDirectory(parent);
      temporary = createPrivateTemporaryFile(parent, file.getName() + '.');
      Files.write(temporary, content);
      Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE);
      PatcherStats.count("cache: written");
      PatcherStats.add("cache: bytes written", content.length);
    } catch (IOException | RuntimeException e) {
      if (temporary != null) {
        deleteIfPresent(temporary.toFile());
      }
      PatcherStats.count("cache: write failed");
      if (writeFailures.incrementAndGet() == MAX_WRITE_FAILURES) {
        log.debug(
            "Stopped writing to the class cache in {} after {} failures, the last one being",
            directory,
            MAX_WRITE_FAILURES,
            e);
      }
    }
  }

  @VisibleForTesting
  int writeFailureCount() {
    return writeFailures.get();
  }

  /** Whether the class loads its class id from a constant whose value is at its class id slot. */
  private static boolean hasClassIdConstant(ProbeClassTransformer.Instrumented instrumented) {
    int slot = instrumented.classIdSlot;
    return slot >= 0
        && slot <= instrumented.bytes.length - 4
        && bigEndianInt(instrumented.bytes, slot) == instrumented.classId;
  }

  private byte[] entryContent(
      byte[] source,
      String className,
      Collection<String> skippedMethods,
      @Nullable ProbeClassTransformer.Instrumented instrumented)
      throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(2 * source.length + 1024);
    CRC32 crc = new CRC32();
    DataOutputStream out = new DataOutputStream(new CheckedOutputStream(bytes, crc));
    out.writeInt(MAGIC);
    out.writeInt(FORMAT);
    out.writeUTF(buildId);
    out.writeInt(source.length);
    out.write(source);
    out.writeUTF(className);
    out.writeInt(skippedMethods.size());
    for (String method : skippedMethods) {
      out.writeUTF(method);
    }
    out.writeBoolean(instrumented != null);
    if (instrumented != null) {
      out.writeUTF(instrumented.sourceFile);
      out.writeBoolean(instrumented.sourceDebug != null);
      if (instrumented.sourceDebug != null) {
        out.writeInt(instrumented.sourceDebug.length());
        out.writeChars(instrumented.sourceDebug);
      }
      int slot = instrumented.classIdSlot;
      byte[] patched = instrumented.bytes;
      out.writeInt(slot);
      out.writeInt(patched.length);
      // the class id is left out: a hit sets the one the class gets in its JVM
      out.write(patched, 0, slot);
      out.writeInt(0);
      out.write(patched, slot + 4, patched.length - slot - 4);
      instrumented.probes.writeTo(out);
    }
    out.flush();
    new DataOutputStream(bytes).writeInt((int) crc.getValue());
    return bytes.toByteArray();
  }

  /** A count of items of at least {@code minLength} bytes each, which must all be left to read. */
  private static int readLength(DataInputStream in, int minLength) throws IOException {
    int length = in.readInt();
    if (length < 0 || length > in.available() / minLength) {
      throw new IOException("length out of range: " + length);
    }
    return length;
  }

  private static String readChars(DataInputStream in) throws IOException {
    char[] chars = new char[readLength(in, 2)];
    for (int k = 0; k < chars.length; k++) {
      chars[k] = in.readChar();
    }
    return new String(chars);
  }

  /**
   * Creates the directory that the test JVMs of a build share as their cache, or returns {@code
   * null} if it cannot, which is logged at warn level. It is a new directory in the temporary
   * directory that only the user can use. Directories left by builds that did not end, a day ago or
   * earlier, are deleted.
   */
  @Nullable
  public static File createBuildDirectory() {
    return createBuildDirectory(null);
  }

  /**
   * @param parent the directory to create it in, or {@code null} for the temporary directory
   */
  @Nullable
  static File createBuildDirectory(@Nullable Path parent) {
    Path directory;
    try {
      directory =
          parent != null
              ? Files.createTempDirectory(parent, BUILD_DIRECTORY_PREFIX)
              : Files.createTempDirectory(BUILD_DIRECTORY_PREFIX);
    } catch (IOException | RuntimeException e) {
      log.warn(
          "Per-test line coverage could not create a class cache directory for the build, so each"
              + " test JVM inserts its own line probes.",
          e);
      return null;
    }
    deleteStaleBuildDirectories(directory);
    return directory.toFile();
  }

  private static void deleteStaleBuildDirectories(Path created) {
    try {
      UserPrincipal user = Files.getOwner(created);
      long staleBefore = System.currentTimeMillis() - STALE_BUILD_DIRECTORY_MILLIS;
      File[] directories =
          created
              .getParent()
              .toFile()
              .listFiles((dir, name) -> name.startsWith(BUILD_DIRECTORY_PREFIX));
      if (directories == null) {
        return;
      }
      for (File stale : directories) {
        Path path = stale.toPath();
        if (!path.equals(created)
            && stale.lastModified() < staleBefore
            && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            && user.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))) {
          deleteBuildDirectory(stale);
        }
      }
    } catch (IOException | RuntimeException e) {
      log.debug("Could not look for stale class cache directories", e);
    }
  }

  /** Deletes a directory that {@link #createBuildDirectory} created, with all it holds. */
  public static void deleteBuildDirectory(File directory) {
    try {
      Files.walkFileTree(
          directory.toPath(),
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException {
              // links are deleted, not followed
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
              if (e != null) {
                throw e;
              }
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException | RuntimeException e) {
      log.debug("Could not delete the class cache directory {}", directory, e);
    }
  }

  private static void deleteIfPresent(File file) {
    if (!file.delete() && file.exists()) {
      log.debug("Could not delete {}", file);
    }
  }

  /** Eight hex digits. */
  private static String hex(long value) {
    return Long.toHexString(value | 0x100000000L).substring(1);
  }

  private static int littleEndianInt(byte[] bytes, int at) {
    return (bytes[at] & 0xFF)
        | (bytes[at + 1] & 0xFF) << 8
        | (bytes[at + 2] & 0xFF) << 16
        | (bytes[at + 3] & 0xFF) << 24;
  }

  private static int bigEndianInt(byte[] bytes, int at) {
    return (bytes[at] & 0xFF) << 24
        | (bytes[at + 1] & 0xFF) << 16
        | (bytes[at + 2] & 0xFF) << 8
        | (bytes[at + 3] & 0xFF);
  }

  /** A class read from the cache. */
  static final class Entry {
    final String className;

    /** The methods the class file patcher left without probes, by name and descriptor. */
    final String[] skippedMethods;

    /** The patched class with its class id left out, or {@code null} if it has no lines. */
    @Nullable private final byte[] bytes;

    @Nullable private final String sourceFile;
    @Nullable private final String sourceDebug;
    private final int classIdSlot;
    @Nullable private final ClassProbesBuilder probes;

    private Entry(
        String className,
        String[] skippedMethods,
        @Nullable byte[] bytes,
        @Nullable String sourceFile,
        @Nullable String sourceDebug,
        int classIdSlot,
        @Nullable ClassProbesBuilder probes) {
      this.className = className;
      this.skippedMethods = skippedMethods;
      this.bytes = bytes;
      this.sourceFile = sourceFile;
      this.sourceDebug = sourceDebug;
      this.classIdSlot = classIdSlot;
      this.probes = probes;
    }

    boolean hasLines() {
      return bytes != null;
    }

    /**
     * The patched class with the class id it gets in this JVM. Called once, on an entry with lines.
     */
    ProbeClassTransformer.Instrumented instrumented(int classId) {
      bytes[classIdSlot] = (byte) (classId >>> 24);
      bytes[classIdSlot + 1] = (byte) (classId >>> 16);
      bytes[classIdSlot + 2] = (byte) (classId >>> 8);
      bytes[classIdSlot + 3] = (byte) classId;
      return new ProbeClassTransformer.Instrumented(
          classId, bytes, className, sourceFile, sourceDebug, probes, classIdSlot);
    }

    /**
     * What differs between the entry and the class patched again with the same class id, or {@code
     * null} if nothing does. Called once, instead of {@link #instrumented}.
     */
    @Nullable
    String difference(
        int classId,
        String patchedClassName,
        Collection<String> patchedSkippedMethods,
        @Nullable ProbeClassTransformer.Instrumented patched) {
      if (!className.equals(patchedClassName)) {
        return "class name " + className;
      }
      if (!new HashSet<>(Arrays.asList(skippedMethods))
          .equals(new HashSet<>(patchedSkippedMethods))) {
        return "methods without probes";
      }
      if (hasLines() != (patched != null)) {
        return hasLines() ? "lines in the cache only" : "lines when patched again only";
      }
      if (patched == null) {
        return null;
      }
      ProbeClassTransformer.Instrumented cached = instrumented(classId);
      if (cached.classIdSlot != patched.classIdSlot) {
        return "class id slot";
      }
      if (!Arrays.equals(cached.bytes, patched.bytes)) {
        return "class file";
      }
      if (!cached.sourceFile.equals(patched.sourceFile)
          || !Objects.equals(cached.sourceDebug, patched.sourceDebug)) {
        return "source file";
      }
      if (!Arrays.equals(serialized(cached.probes), serialized(patched.probes))) {
        return "probes";
      }
      return null;
    }

    private static byte[] serialized(ClassProbesBuilder probes) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try {
        probes.writeTo(new DataOutputStream(bytes));
      } catch (IOException e) {
        throw new UncheckedIOException(e); // a byte array stream does not throw
      }
      return bytes.toByteArray();
    }
  }
}
