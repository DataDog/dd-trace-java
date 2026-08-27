package datadog.trace.bootstrap;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Generates comparable agent-jar layouts for the class-data benchmarks. */
public final class ClassDataBenchmarkArtifacts {
  private static final int[] STORED_PERCENTAGES = {10, 25, 50, 75, 100};
  private static final int[] PACKED_CHUNK_SIZES = {64, 256, 1024};
  private static final int PACKED_HEADER_SIZE = 20;
  private static final int PACKED_RECORD_SIZE = 24;
  private static final Pattern MODERN_CLASS_LOAD =
      Pattern.compile(".*\\[class,load] ([^ ]+) source:.*");
  private static final Pattern LEGACY_CLASS_LOAD = Pattern.compile("\\[Loaded ([^ ]+) from .*");
  private static final String COMMON_CLASSES_FILE = "common-classes.txt";

  private ClassDataBenchmarkArtifacts() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 4 || args.length > 6) {
      throw new IllegalArgumentException(
          "Expected: <agent-jar> <output-dir> <java-executable> <benchmark-jar> "
              + "[scenario] [profile-only]");
    }
    File agentJar = new File(args[0]);
    Path outputDir = new File(args[1]).toPath();
    Files.createDirectories(outputDir);

    String scenario = args.length == 5 ? args[4] : "default";
    if (args.length == 6) {
      scenario = args[4];
    }
    boolean profileOnly = args.length == 6 && "profile-only".equals(args[5]);
    if (args.length == 6 && !profileOnly) {
      throw new IllegalArgumentException("Unknown class-data benchmark mode " + args[5]);
    }
    List<String> commonClasses = discoverCommonClasses(agentJar, args[2], args[3], scenario);
    if (commonClasses.isEmpty()) {
      throw new IllegalStateException("No common .classdata classes were discovered");
    }
    Files.write(
        outputDir.resolve(COMMON_CLASSES_FILE),
        commonClasses,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);

    long classBytes = 0;
    try (JarFile jar = new JarFile(agentJar)) {
      AgentJarIndex index = AgentJarIndex.readIndex(jar);
      for (String className : commonClasses) {
        classBytes += jar.getJarEntry(index.classEntryName(className)).getSize();
      }
    }
    if (!profileOnly) {
      rewrite(agentJar, outputDir.resolve("baseline.jar"), commonClasses, 0, null);
      for (int percentage : STORED_PERCENTAGES) {
        int storedCount = percentageCount(commonClasses.size(), percentage);
        rewrite(
            agentJar,
            outputDir.resolve("stored-" + percentage + ".jar"),
            commonClasses,
            storedCount,
            null);
      }
      for (int chunkSize : PACKED_CHUNK_SIZES) {
        PackedArchive packed = createPackedData(agentJar, commonClasses, chunkSize);
        rewrite(
            agentJar, outputDir.resolve("packed-" + chunkSize + ".jar"), commonClasses, 0, packed);
        if (chunkSize == 64) {
          rewrite(
              agentJar,
              outputDir.resolve("packed-64-dedup.jar"),
              commonClasses,
              0,
              packed.withoutIndividualEntries());
        }
      }
      PackedArchive packed = createPackedData(agentJar, commonClasses, commonClasses.size());
      rewrite(agentJar, outputDir.resolve("packed-all.jar"), commonClasses, 0, packed);
    }
    System.out.printf(
        "Prepared %d common classes (%,d bytes) for %s in %s%n",
        commonClasses.size(), classBytes, scenario, outputDir);
  }

  private static List<String> discoverCommonClasses(
      File agentJar, String javaExecutable, String benchmarkJar, String scenario) throws Exception {
    Set<String> classEntries = new HashSet<>();
    try (JarFile jar = new JarFile(agentJar)) {
      AgentJarIndex index = AgentJarIndex.readIndex(jar);
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        String entryName = entries.nextElement().getName();
        if (entryName.endsWith(".classdata")) {
          int prefixEnd = entryName.indexOf('/');
          if (prefixEnd > 0) {
            String className =
                entryName
                    .substring(prefixEnd + 1, entryName.length() - ".classdata".length())
                    .replace('/', '.');
            String indexedEntry = index.classEntryName(className);
            if (entryName.equals(indexedEntry)) {
              classEntries.add(className);
            }
          }
        }
      }
    }

    List<String> command = new ArrayList<>();
    command.add(javaExecutable);
    command.add("-verbose:class");
    command.addAll(agentOptions(scenario));
    command.add("-javaagent:" + agentJar.getAbsolutePath());
    command.add("-cp");
    command.add(benchmarkJar);
    command.add(ClassDataBenchmarkTarget.class.getName());
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

    Set<String> discovered = new LinkedHashSet<>();
    boolean mainReached = false;
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (ClassDataBenchmarkTarget.READY.equals(line)) {
          mainReached = true;
        } else if (!mainReached) {
          String className = loadedClassName(line);
          if (classEntries.contains(className)) {
            discovered.add(className);
          }
        }
      }
    }
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("Timed out discovering common classdata classes");
    }
    if (process.exitValue() != 0) {
      throw new IOException("Classdata discovery process exited with " + process.exitValue());
    }
    if (!mainReached) {
      throw new IOException("Classdata discovery process did not reach application main");
    }
    List<String> result = new ArrayList<>(discovered);
    return result;
  }

  static List<String> agentOptions(String scenario) {
    List<String> options =
        new ArrayList<>(
            Arrays.asList(
                "-Ddd.jmxfetch.enabled=false",
                "-Ddd.telemetry.enabled=false",
                "-Ddd.remote_config.enabled=false",
                "-Ddd.writer.type=LoggingWriter"));
    if ("default".equals(scenario)) {
      return options;
    }
    if ("profiling".equals(scenario)) {
      options.add("-Ddd.profiling.enabled=true");
      return options;
    }
    if ("appsec".equals(scenario)) {
      options.add("-Ddd.appsec.enabled=true");
      return options;
    }
    throw new IllegalArgumentException("Unknown class-data benchmark scenario " + scenario);
  }

  private static int percentageCount(int size, int percentage) {
    return Math.max(1, (size * percentage + 99) / 100);
  }

  private static String loadedClassName(String line) {
    Matcher matcher = MODERN_CLASS_LOAD.matcher(line);
    if (matcher.matches()) {
      return matcher.group(1);
    }
    matcher = LEGACY_CLASS_LOAD.matcher(line);
    return matcher.matches() ? matcher.group(1) : null;
  }

  static PackedArchive createPackedData(File agentJar, List<String> commonClasses, int chunkSize)
      throws IOException {
    if (chunkSize <= 0) {
      throw new IllegalArgumentException("Chunk size must be positive");
    }
    List<PackedRecord> records = new ArrayList<>(commonClasses.size());
    List<byte[]> chunks = new ArrayList<>();
    try (JarFile jar = new JarFile(agentJar)) {
      AgentJarIndex index = AgentJarIndex.readIndex(jar);
      ByteArrayOutputStream chunk = null;
      for (int i = 0; i < commonClasses.size(); i++) {
        if (i % chunkSize == 0) {
          if (chunk != null) {
            chunks.add(chunk.toByteArray());
          }
          chunk = new ByteArrayOutputStream();
        }
        String className = commonClasses.get(i);
        byte[] classData = readAll(jar, jar.getJarEntry(index.classEntryName(className)));
        records.add(new PackedRecord(className, i / chunkSize, chunk.size(), classData.length));
        chunk.write(classData);
      }
      if (chunk != null) {
        chunks.add(chunk.toByteArray());
      }
    }

    int tableSize = 1;
    while (tableSize < records.size() * 2) {
      tableSize <<= 1;
    }
    int namesOffset = PACKED_HEADER_SIZE + tableSize * PACKED_RECORD_SIZE;
    int namesSize = 0;
    for (PackedRecord record : records) {
      record.nameOffset = namesOffset + namesSize;
      namesSize += record.className.length() * 2;
    }
    byte[] packedIndex = new byte[namesOffset + namesSize];
    writeInt(packedIndex, 0, PackedClassData.MAGIC);
    writeInt(packedIndex, 4, PackedClassData.VERSION);
    writeInt(packedIndex, 8, records.size());
    writeInt(packedIndex, 12, chunks.size());
    writeInt(packedIndex, 16, tableSize);
    boolean[] occupied = new boolean[tableSize];
    for (PackedRecord packedRecord : records) {
      int slot = PackedClassData.spread(packedRecord.className.hashCode()) & (tableSize - 1);
      while (occupied[slot]) {
        slot = (slot + 1) & (tableSize - 1);
      }
      occupied[slot] = true;
      int recordOffset = PACKED_HEADER_SIZE + slot * PACKED_RECORD_SIZE;
      writeInt(packedIndex, recordOffset, packedRecord.className.hashCode());
      writeInt(packedIndex, recordOffset + 4, packedRecord.nameOffset);
      writeInt(packedIndex, recordOffset + 8, packedRecord.className.length());
      writeInt(packedIndex, recordOffset + 12, packedRecord.chunk);
      writeInt(packedIndex, recordOffset + 16, packedRecord.offset);
      writeInt(packedIndex, recordOffset + 20, packedRecord.length);
      int nameOffset = packedRecord.nameOffset;
      for (int i = 0; i < packedRecord.className.length(); i++) {
        char character = packedRecord.className.charAt(i);
        packedIndex[nameOffset++] = (byte) (character >>> 8);
        packedIndex[nameOffset++] = (byte) character;
      }
    }

    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put(PackedClassData.ENTRY_NAME, packedIndex);
    for (int chunk = 0; chunk < chunks.size(); chunk++) {
      entries.put(PackedClassData.chunkEntryName(chunk), chunks.get(chunk));
    }
    return new PackedArchive(entries);
  }

  private static void rewrite(
      File source,
      Path target,
      List<String> commonClasses,
      int storedCount,
      PackedArchive packedData)
      throws IOException {
    Set<String> commonEntries = new LinkedHashSet<>();
    try (JarFile jar = new JarFile(source)) {
      AgentJarIndex index = AgentJarIndex.readIndex(jar);
      int indexedCount =
          packedData != null && packedData.removeIndividualEntries
              ? commonClasses.size()
              : storedCount;
      for (int i = 0; i < indexedCount; i++) {
        String className = commonClasses.get(i);
        commonEntries.add(index.classEntryName(className));
      }
    }

    Files.deleteIfExists(target);
    try (JarFile input = new JarFile(source);
        OutputStream fileOutput = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
        ZipOutputStream output = new ZipOutputStream(fileOutput)) {
      Enumeration<JarEntry> entries = input.entries();
      while (entries.hasMoreElements()) {
        JarEntry original = entries.nextElement();
        if (packedData != null
            && packedData.removeIndividualEntries
            && commonEntries.contains(original.getName())) {
          continue;
        }
        byte[] content = original.isDirectory() ? new byte[0] : readAll(input, original);
        ZipEntry copy = new ZipEntry(original.getName());
        copy.setTime(original.getTime());
        if (storedCount > 0 && commonEntries.contains(original.getName())) {
          setStored(copy, content);
        }
        output.putNextEntry(copy);
        output.write(content);
        output.closeEntry();
      }
      if (packedData != null) {
        for (Map.Entry<String, byte[]> packedEntry : packedData.entries.entrySet()) {
          output.putNextEntry(new ZipEntry(packedEntry.getKey()));
          output.write(packedEntry.getValue());
          output.closeEntry();
        }
      }
    }
  }

  private static void writeInt(byte[] target, int offset, int value) {
    target[offset] = (byte) (value >>> 24);
    target[offset + 1] = (byte) (value >>> 16);
    target[offset + 2] = (byte) (value >>> 8);
    target[offset + 3] = (byte) value;
  }

  static final class PackedArchive {
    final Map<String, byte[]> entries;
    final boolean removeIndividualEntries;

    private PackedArchive(Map<String, byte[]> entries) {
      this(entries, false);
    }

    private PackedArchive(Map<String, byte[]> entries, boolean removeIndividualEntries) {
      this.entries = entries;
      this.removeIndividualEntries = removeIndividualEntries;
    }

    private PackedArchive withoutIndividualEntries() {
      return new PackedArchive(entries, true);
    }
  }

  private static final class PackedRecord {
    private final String className;
    private final int chunk;
    private final int offset;
    private final int length;
    private int nameOffset;

    private PackedRecord(String className, int chunk, int offset, int length) {
      this.className = className;
      this.chunk = chunk;
      this.offset = offset;
      this.length = length;
    }
  }

  private static void setStored(ZipEntry entry, byte[] content) {
    CRC32 crc = new CRC32();
    crc.update(content);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(content.length);
    entry.setCompressedSize(content.length);
    entry.setCrc(crc.getValue());
  }

  private static byte[] readAll(JarFile jar, JarEntry entry) throws IOException {
    if (entry == null || entry.getSize() < 0 || entry.getSize() > Integer.MAX_VALUE) {
      throw new IOException("Invalid jar entry");
    }
    byte[] bytes = new byte[(int) entry.getSize()];
    try (InputStream input = jar.getInputStream(entry)) {
      int offset = 0;
      while (offset < bytes.length) {
        int read = input.read(bytes, offset, bytes.length - offset);
        if (read < 0) {
          throw new IOException("Truncated jar entry " + entry.getName());
        }
        offset += read;
      }
    }
    return bytes;
  }
}
