package datadog.trace.civisibility.coverage.probe;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_MODULE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_6;
import static org.objectweb.asm.Opcodes.V1_8;

import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.api.civisibility.coverage.SharedPart;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * The class cache gives the class that patching in the JVM gives, with the class id of the JVM, and
 * falls back to patching in the JVM on any entry it cannot use.
 */
class ProbeClassCacheTest {

  private static final String PACKAGE = "datadog/trace/civisibility/coverage/probe/";
  private static final String NAME = PACKAGE + "CachedClass";

  /** A class id other than the one an entry was written with. */
  private static final int OTHER_CLASS_ID = 40_000;

  /**
   * A time long past, for files whose content must not depend on the time, or whose rewrite shows.
   */
  private static final long FIXED_TIME_MILLIS = 1_000_000_000_000L;

  private static final boolean POSIX =
      FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "kotlin/Unit.class",
        "scala/Option.class",
        "groovy/lang/GroovyObject.class",
        "okio/Buffer.class",
        "com/fasterxml/jackson/databind/ObjectMapper.class",
        "org/mockito/Mockito.class",
      })
  void cachedClassesEqualClassesPatchedAgainWithAnotherClassId(String markerClass)
      throws Exception {
    URL marker = getClass().getClassLoader().getResource(markerClass);
    assumeTrue(marker != null && "jar".equals(marker.getProtocol()), "not on the classpath");
    JarFile jar = ((JarURLConnection) marker.openConnection()).getJarFile();
    ProbeClassCache cache = open(new File(jar.getName()));

    List<String> differences = new ArrayList<>();
    int compared = 0;
    for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
      JarEntry jarEntry = entries.nextElement();
      if (!jarEntry.getName().endsWith(".class") || jarEntry.getName().startsWith("META-INF/")) {
        continue;
      }
      byte[] source;
      try (InputStream in = jar.getInputStream(jarEntry)) {
        source = readAll(in);
      }
      int majorVersion = ((source[6] & 0xFF) << 8) | (source[7] & 0xFF);
      if (majorVersion < V1_6) {
        continue;
      }
      ClassFile classFile = new ClassFile(source);
      if ((classFile.access & ACC_MODULE) != 0) {
        continue;
      }
      String className = classFile.className(classFile.thisClass);
      List<String> skippedMethods = new ArrayList<>();
      ProbeClassTransformer.Instrumented patched;
      try {
        patched = ClassFilePatcher.instrument(classFile, 7, true, skippedMethods);
      } catch (RuntimeException e) {
        continue; // left to ASM, so never cached
      }
      File file = cache.entryFile(source);
      cache.write(file, source, className, skippedMethods, patched);
      ProbeClassCache.Entry entry = cache.read(file, source);
      if (entry == null) {
        differences.add(className + ": not read back");
        continue;
      }

      List<String> skippedAgain = new ArrayList<>();
      ProbeClassTransformer.Instrumented patchedAgain =
          ClassFilePatcher.instrument(new ClassFile(source), OTHER_CLASS_ID, true, skippedAgain);
      String difference = entry.difference(OTHER_CLASS_ID, className, skippedAgain, patchedAgain);
      if (difference != null) {
        differences.add(className + ": " + difference);
      }
      compared++;
    }

    assertEquals(new ArrayList<>(), differences);
    assertTrue(compared > 0, "no class was compared");
    assertEquals(0, cache.writeFailureCount());
  }

  @Test
  void aClassFromTheCacheRecordsItsLinesUnderItsOwnClassId() throws Exception {
    ProbeClassCache cache = open(agentJar());
    byte[] source = generate(NAME, true, 0);

    GeneratedClassLoader first = new GeneratedClassLoader();
    byte[] patched = ProbeClassTransformer.instrument(source, first, true, cache);
    File file = cache.entryFile(source);
    assertTrue(file.isFile(), "the entry was not written");
    long writtenAt = FIXED_TIME_MILLIS;
    assertTrue(file.setLastModified(writtenAt));

    GeneratedClassLoader second = new GeneratedClassLoader();
    byte[] fromCache = ProbeClassTransformer.instrument(source, second, true, cache);

    assertEquals(writtenAt, file.lastModified(), "the entry was written again, so not used");
    assertNotNull(patched);
    assertNotNull(fromCache);
    assertEquals(patched.length, fromCache.length);
    int differingBytes = 0;
    for (int k = 0; k < patched.length; k++) {
      if (patched[k] != fromCache[k]) {
        differingBytes++;
      }
    }
    assertTrue(differingBytes >= 1 && differingBytes <= 4, differingBytes + " bytes differ");

    assertEquals(1, first.define(NAME, patched).getMethod("one").invoke(null));
    assertEquals(lines(1), coveredLines(first));
    assertEquals(1, second.define(NAME, fromCache).getMethod("one").invoke(null));
    assertEquals(lines(1), coveredLines(second));
  }

  @Test
  void aClassWithManyConstantsLoadsItsClassIdWithAWideIndex() throws Exception {
    ProbeClassCache cache = open(agentJar());
    byte[] source = generate(NAME, true, 400);

    GeneratedClassLoader first = new GeneratedClassLoader();
    byte[] patched = ProbeClassTransformer.instrument(source, first, true, cache);
    GeneratedClassLoader second = new GeneratedClassLoader();
    byte[] fromCache = ProbeClassTransformer.instrument(source, second, true, cache);

    assertNotNull(patched);
    assertNotNull(fromCache);
    assertEquals(1, first.define(NAME, patched).getMethod("one").invoke(null));
    assertEquals(1, second.define(NAME, fromCache).getMethod("one").invoke(null));
    assertEquals(lines(1), coveredLines(second));
  }

  @Test
  void aDamagedEntryIsAMissAndIsWrittenAgain() throws Exception {
    ProbeClassCache cache = open(agentJar());
    byte[] source = generate(NAME, true, 0);
    assertNotNull(
        ProbeClassTransformer.instrument(source, new GeneratedClassLoader(), true, cache));
    File file = cache.entryFile(source);
    byte[] content = Files.readAllBytes(file.toPath());

    byte[] flipped = content.clone();
    flipped[content.length / 2] ^= 1;
    Files.write(file.toPath(), flipped);
    assertNull(cache.read(file, source));

    Files.write(file.toPath(), Arrays.copyOf(content, content.length - 7));
    assertNull(cache.read(file, source));

    Files.write(file.toPath(), new byte[0]);
    assertNull(cache.read(file, source));

    GeneratedClassLoader loader = new GeneratedClassLoader();
    byte[] patchedAgain = ProbeClassTransformer.instrument(source, loader, true, cache);
    assertNotNull(patchedAgain);
    assertNotNull(cache.read(file, source), "the entry was not written again");
    assertEquals(1, loader.define(NAME, patchedAgain).getMethod("one").invoke(null));
  }

  @Test
  void anEntryOfAnotherAgentBuildIsNotUsed() throws Exception {
    File root = temporaryDirectory.resolve("cache").toFile();
    assertTrue(root.mkdir());
    ProbeClassCache build = ProbeClassCache.open(root, jar("a.jar", "one build"));
    ProbeClassCache otherBuild = ProbeClassCache.open(root, jar("b.jar", "another build"));
    assertNotNull(build);
    assertNotNull(otherBuild);
    byte[] source = generate(NAME, true, 0);
    assertNotNull(
        ProbeClassTransformer.instrument(source, new GeneratedClassLoader(), true, build));

    File file = build.entryFile(source);
    File otherFile = otherBuild.entryFile(source);
    assertNotEquals(file, otherFile);
    assertNull(otherBuild.read(otherFile, source));

    assertTrue(otherFile.getParentFile().mkdirs());
    Files.copy(file.toPath(), otherFile.toPath());
    assertNull(otherBuild.read(otherFile, source), "the build id in the entry was not checked");
    assertNotNull(build.read(file, source));
  }

  @Test
  void anEntryForOtherBytesIsNotUsed() throws Exception {
    ProbeClassCache cache = open(agentJar());
    byte[] source = generate(NAME, true, 0);
    byte[] otherSource = generate(NAME, true, 1);
    assertNotNull(
        ProbeClassTransformer.instrument(source, new GeneratedClassLoader(), true, cache));

    assertNull(cache.read(cache.entryFile(source), otherSource));
  }

  @Test
  void aClassWithoutLinesIsCachedAsSuch() throws Exception {
    ProbeClassCache cache = open(agentJar());
    byte[] source = generate(NAME, false, 0);

    assertNull(ProbeClassTransformer.instrument(source, new GeneratedClassLoader(), true, cache));
    ProbeClassCache.Entry entry = cache.read(cache.entryFile(source), source);
    assertNotNull(entry);
    assertFalse(entry.hasLines());
    assertNull(ProbeClassTransformer.instrument(source, new GeneratedClassLoader(), true, cache));
  }

  @Test
  void methodsLeftWithoutProbesAreReportedOnHits() throws Exception {
    ProbeClassCache cache = open(agentJar());
    String name = PACKAGE + "CachedClassWithALoss";
    byte[] source = generate(name, true, 0);
    ProbeClassTransformer.Instrumented patched =
        ClassFilePatcher.instrument(new ClassFile(source), 3, true, new ArrayList<>());
    cache.write(cache.entryFile(source), source, name, singletonList("lost()V"), patched);
    int before = ProbeLosses.methodCount();

    assertNotNull(
        ProbeClassTransformer.instrument(source, new GeneratedClassLoader(), true, cache));

    assertEquals(before + 1, ProbeLosses.methodCount());
    StringWriter losses = new StringWriter();
    ProbeLosses.writeTo(losses);
    assertTrue(
        losses
            .toString()
            .contains("method " + name + ".lost()V: " + ProbeClassTransformer.NO_PROBE_PLACE),
        losses.toString());
  }

  @Test
  void aCacheThatCannotBeWrittenIsReadAndStopsWriting() throws Exception {
    assumeTrue(POSIX && !"root".equals(System.getProperty("user.name")), "permissions apply");
    File root = temporaryDirectory.resolve("cache").toFile();
    assertTrue(root.mkdir());
    ProbeClassCache cache = ProbeClassCache.open(root, agentJar());
    assertNotNull(cache);
    byte[] source = generate(NAME, true, 0);
    assertNotNull(
        ProbeClassTransformer.instrument(source, new GeneratedClassLoader(), true, cache));
    List<Path> directories = directories(root.toPath());
    try {
      for (Path directory : directories) {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
      }

      assertNotNull(cache.read(cache.entryFile(source), source));
      for (int k = 0; k < ProbeClassCache.MAX_WRITE_FAILURES + 5; k++) {
        byte[] other = generate(PACKAGE + "NotCached" + k, true, 0);
        GeneratedClassLoader loader = new GeneratedClassLoader();
        byte[] patched = ProbeClassTransformer.instrument(other, loader, true, cache);
        assertNotNull(patched);
        assertEquals(
            1, loader.define(PACKAGE + "NotCached" + k, patched).getMethod("one").invoke(null));
      }
      assertEquals(ProbeClassCache.MAX_WRITE_FAILURES, cache.writeFailureCount());
    } finally {
      for (Path directory : directories) {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
      }
    }
    assertEquals(new ArrayList<>(), temporaryFiles(root.toPath()));
  }

  @Test
  void writersOfTheSameEntryLeaveOneWholeEntry() throws Exception {
    ProbeClassCache cache = open(agentJar());
    byte[] source = generate(NAME, true, 0);
    ProbeClassTransformer.Instrumented patched =
        ClassFilePatcher.instrument(new ClassFile(source), 5, true, new ArrayList<>());
    File file = cache.entryFile(source);
    CountDownLatch start = new CountDownLatch(1);
    List<Thread> writers = new ArrayList<>();
    for (int t = 0; t < 8; t++) {
      Thread writer =
          new Thread(() -> {
            try {
              start.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
            for (int k = 0; k < 50; k++) {
              cache.write(file, source, NAME, emptyList(), patched);
            }
          });
      writer.start();
      writers.add(writer);
    }
    start.countDown();
    for (Thread writer : writers) {
      writer.join(TimeUnit.MINUTES.toMillis(1));
    }

    assertNotNull(cache.read(file, source));
    assertEquals(0, cache.writeFailureCount());
    assertEquals(new ArrayList<>(), temporaryFiles(file.getParentFile().toPath()));
  }

  @Test
  void aDirectoryThatOtherUsersCanWriteIsNotUsed() throws Exception {
    assumeTrue(POSIX, "POSIX permissions");
    Path shared = Files.createDirectory(temporaryDirectory.resolve("shared"));
    Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
    assertNotNull(ProbeClassCache.checkDirectory(shared.toFile()));
    assertNull(ProbeClassCache.open(shared.toFile(), agentJar()));

    Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxr-xr-x"));
    assertNull(ProbeClassCache.checkDirectory(shared.toFile()));
    assertNotNull(ProbeClassCache.open(shared.toFile(), agentJar()));

    assertNotNull(ProbeClassCache.checkDirectory(temporaryDirectory.resolve("missing").toFile()));
  }

  @Test
  void theDirectoriesAndEntriesOfABuildAreTheUsersAlone() throws Exception {
    assumeTrue(POSIX, "POSIX permissions");
    Path root = Files.createDirectory(temporaryDirectory.resolve("kept"));
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
    ProbeClassCache cache = ProbeClassCache.open(root.toFile(), agentJar());
    assertNotNull(cache);
    byte[] source = generate(NAME, true, 0);
    assertNotNull(
        ProbeClassTransformer.instrument(source, new GeneratedClassLoader(), true, cache));

    File entry = cache.entryFile(source);
    assertTrue(entry.isFile(), "the entry was not written");
    assertEquals("rw-------", permissions(entry.toPath()));
    assertEquals("rwx------", permissions(entry.getParentFile().toPath()));
    assertEquals("rwx------", permissions(entry.getParentFile().getParentFile().toPath()));
  }

  @Test
  void aBuildDirectoryThatOtherUsersCanReachIsNotUsed() throws Exception {
    assumeTrue(POSIX, "POSIX permissions");
    Path root = Files.createDirectory(temporaryDirectory.resolve("kept"));
    String buildId = ProbeClassCache.buildId(agentJar());
    assertNotNull(buildId);
    Path build = Files.createDirectory(root.resolve(buildId));
    Files.setPosixFilePermissions(build, PosixFilePermissions.fromString("rwx--x--x"));
    assertNotNull(ProbeClassCache.checkBuildDirectory(build));
    assertNull(ProbeClassCache.open(root.toFile(), agentJar()));

    Files.setPosixFilePermissions(build, PosixFilePermissions.fromString("rwx------"));
    assertNull(ProbeClassCache.checkBuildDirectory(build));
    assertNotNull(ProbeClassCache.open(root.toFile(), agentJar()));

    Path moved = Files.move(build, root.resolve("moved"));
    Files.createSymbolicLink(build, moved);
    assertNotNull(ProbeClassCache.checkBuildDirectory(build));
    assertNull(ProbeClassCache.open(root.toFile(), agentJar()), "a link was followed");
  }

  @Test
  void theBuildIdChangesWithTheJarContent() throws Exception {
    String id = ProbeClassCache.buildId(jar("a.jar", "content"));
    assertNotNull(id);
    assertEquals(id, ProbeClassCache.buildId(jar("b.jar", "content")));
    assertNotEquals(id, ProbeClassCache.buildId(jar("c.jar", "other content")));

    Path notAJar = temporaryDirectory.resolve("not-a.jar");
    Files.write(notAJar, new byte[100]);
    assertNull(ProbeClassCache.buildId(notAJar.toFile()));
    assertNull(ProbeClassCache.buildId(temporaryDirectory.resolve("missing.jar").toFile()));
  }

  @Test
  void aBuildDirectoryIsPrivateAndIsDeletedWithItsEntries() throws Exception {
    File directory = ProbeClassCache.createBuildDirectory(temporaryDirectory);
    assertNotNull(directory);
    if (POSIX) {
      assertEquals("rwx------", permissions(directory.toPath()));
    }
    ProbeClassCache cache = ProbeClassCache.open(directory, agentJar());
    assertNotNull(cache);
    byte[] source = generate(NAME, true, 0);
    assertNotNull(
        ProbeClassTransformer.instrument(source, new GeneratedClassLoader(), true, cache));
    Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
    Path kept = Files.write(outside.resolve("kept"), new byte[] {1});
    Files.createSymbolicLink(directory.toPath().resolve("link"), outside);

    ProbeClassCache.deleteBuildDirectory(directory);

    assertFalse(directory.exists());
    assertTrue(Files.exists(kept), "a link was followed");
  }

  @Test
  void buildDirectoriesLeftADayAgoAreDeleted() throws Exception {
    long twoDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2);
    Path stale = Files.createDirectory(temporaryDirectory.resolve("dd-line-probes-stale"));
    Files.write(stale.resolve("entry"), new byte[] {1});
    assertTrue(stale.toFile().setLastModified(twoDaysAgo));
    Path recent = Files.createDirectory(temporaryDirectory.resolve("dd-line-probes-recent"));
    Path other = Files.createDirectory(temporaryDirectory.resolve("other"));
    assertTrue(other.toFile().setLastModified(twoDaysAgo));
    Path target = Files.createDirectory(temporaryDirectory.resolve("target"));
    Path kept = Files.write(target.resolve("kept"), new byte[] {1});
    assertTrue(target.toFile().setLastModified(twoDaysAgo));
    Path link = Files.createSymbolicLink(temporaryDirectory.resolve("dd-line-probes-link"), target);

    File created = ProbeClassCache.createBuildDirectory(temporaryDirectory);

    assertNotNull(created);
    assertTrue(created.isDirectory());
    assertFalse(Files.exists(stale));
    assertTrue(Files.exists(recent));
    assertTrue(Files.exists(other));
    assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.exists(kept), "a link was followed");
  }

  private ProbeClassCache open(File agentJar) throws IOException {
    Path root = Files.createDirectories(temporaryDirectory.resolve("cache"));
    ProbeClassCache cache = ProbeClassCache.open(root.toFile(), agentJar);
    assertNotNull(cache);
    return cache;
  }

  /** A jar with one entry of fixed time, so that the same content gives the same jar. */
  private File jar(String name, String content) throws IOException {
    File jar = temporaryDirectory.resolve(name).toFile();
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
      JarEntry entry = new JarEntry("content.txt");
      entry.setTime(FIXED_TIME_MILLIS);
      out.putNextEntry(entry);
      out.write(content.getBytes(UTF_8));
      out.closeEntry();
    }
    return jar;
  }

  private File agentJar() throws IOException {
    File jar = temporaryDirectory.resolve("agent.jar").toFile();
    return jar.isFile() ? jar : jar("agent.jar", "agent");
  }

  private static List<Path> directories(Path root) throws IOException {
    List<Path> directories = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(root)) {
      paths.filter(Files::isDirectory).forEach(directories::add);
    }
    return directories;
  }

  private static String permissions(Path path) throws IOException {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
  }

  private static List<Path> temporaryFiles(Path root) throws IOException {
    List<Path> temporary = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(root)) {
      paths.filter(path -> path.toString().endsWith(".tmp")).forEach(temporary::add);
    }
    return temporary;
  }

  private static BitSet lines(int... lines) {
    BitSet set = new BitSet();
    for (int line : lines) {
      set.set(line);
    }
    return set;
  }

  private static BitSet coveredLines(ClassLoader loader) {
    BitSet lines = new BitSet();
    ProbeRecorder.flushShared();
    for (int classId = 0; classId < ProbeRecorder.getClassCount(); classId++) {
      InstrumentedClass instrumentedClass = ProbeRecorder.getInstrumentedClass(classId);
      if (instrumentedClass != null && instrumentedClass.loader() == loader) {
        ProbeRecorder.addSharedLines(SharedPart.UNOWNED, classId, lines);
      }
    }
    return lines;
  }

  /**
   * A class with {@code static int one()} on line 1, which returns 1, and a method that loads a
   * number of distinct strings, which fill the constant pool, on line 2. Without a source file, it
   * has no lines to cover.
   */
  private static byte[] generate(String name, boolean withSourceFile, int strings) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
    writer.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", null);
    if (withSourceFile) {
      writer.visitSource("CachedClass.java", null);
    }
    MethodVisitor one = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "one", "()I", null, null);
    one.visitCode();
    line(one, 1);
    one.visitInsn(ICONST_1);
    one.visitInsn(IRETURN);
    one.visitMaxs(0, 0);
    one.visitEnd();
    if (strings > 0) {
      MethodVisitor fill = writer.visitMethod(ACC_STATIC, "fill", "()V", null, null);
      fill.visitCode();
      line(fill, 2);
      for (int k = 0; k < strings; k++) {
        fill.visitLdcInsn("constant " + k);
        fill.visitInsn(POP);
      }
      fill.visitInsn(RETURN);
      fill.visitMaxs(0, 0);
      fill.visitEnd();
    }
    writer.visitEnd();
    return writer.toByteArray();
  }

  private static void line(MethodVisitor code, int line) {
    Label label = new Label();
    code.visitLabel(label);
    code.visitLineNumber(line, label);
  }

  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    for (int read; (read = in.read(buffer)) != -1; ) {
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private static final class GeneratedClassLoader extends ClassLoader {
    GeneratedClassLoader() {
      super(ProbeClassCacheTest.class.getClassLoader());
    }

    Class<?> define(String name, byte[] bytes) {
      return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
    }
  }
}
