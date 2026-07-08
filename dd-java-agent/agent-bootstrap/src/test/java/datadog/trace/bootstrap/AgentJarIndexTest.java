package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentJarIndexTest {

  // --- computeEntryKey tests ---

  private static String computeEntryKey(String pathStr) {
    return AgentJarIndex.IndexGenerator.computeEntryKey(Paths.get(pathStr));
  }

  @Test
  void computeEntryKeyReturnsNullForIgnoredFiles() {
    assertNull(computeEntryKey("MANIFEST.MF"));
    assertNull(computeEntryKey("NOTICE"));
    assertNull(computeEntryKey("LICENSE.renamed"));
  }

  @Test
  void computeEntryKeyReturnsWildcardForInstrumentationSubtree() {
    assertEquals(
        "datadog.trace.instrumentation.*",
        computeEntryKey("datadog/trace/instrumentation/servlet/ServletAdvice.classdata"));
    assertEquals(
        "datadog.trace.instrumentation.*",
        computeEntryKey("datadog/trace/instrumentation/SomeAdvice.classdata"));
  }

  @Test
  void computeEntryKeyReturnsWildcardForDeepPaths() {
    // 3+ path elements → wildcard on parent directory, regardless of file extension
    assertEquals("com.example.*", computeEntryKey("com/example/Foo.classdata"));
    assertEquals("com.example.*", computeEntryKey("com/example/Foo.xml"));
    assertEquals("com.example.sub.*", computeEntryKey("com/example/sub/Foo.classdata"));
  }

  @Test
  void computeEntryKeyReturnsFullPathForSingleElementPaths() {
    // nameCount == 1 skips the wildcard block entirely
    assertEquals("Foo.classdata", computeEntryKey("Foo.classdata"));
    assertEquals("some-lib.jar", computeEntryKey("some-lib.jar"));
  }

  @Test
  void computeEntryKeyReturnsWildcardForClassdataAtTwoLevels() {
    // 2-level .classdata files are deep enough to use a wildcard
    assertEquals("com.*", computeEntryKey("com/Foo.classdata"));
  }

  @Test
  void computeEntryKeyReturnsFullPathForShallowNonClassdataFiles() {
    // 2-level non-.classdata files keep their full dot-separated name
    assertEquals("com.Foo.class", computeEntryKey("com/Foo.class"));
    assertEquals("META-INF.services.Foo", computeEntryKey("META-INF/services/Foo"));
  }

  @Test
  void computeEntryKeyDoesNotCountMetaInfAsUniqueElement() {
    // META-INF/x/y → nameCount effectively 2 → no wildcard
    assertEquals("META-INF.services.SomeService", computeEntryKey("META-INF/services/SomeService"));
    // META-INF/x/y/z → nameCount effectively 3 → wildcard
    assertEquals("META-INF.services.com.*", computeEntryKey("META-INF/services/com/SomeService"));
  }

  // --- buildIndex / writeIndex / readIndex round-trip ---

  /** Creates a temp JAR containing only the index file written by the generator. */
  private static AgentJarIndex buildAndReadIndex(Path resourcesDir, Path tempDir) throws Exception {
    AgentJarIndex.IndexGenerator generator = new AgentJarIndex.IndexGenerator(resourcesDir);
    generator.buildIndex();

    Path indexFile = tempDir.resolve("dd-java-agent.index");
    generator.writeIndex(indexFile);

    Path jarFile = tempDir.resolve("test-agent.jar");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jarFile))) {
      zip.putNextEntry(new ZipEntry("dd-java-agent.index"));
      Files.copy(indexFile, zip);
      zip.closeEntry();
    }

    try (JarFile jar = new JarFile(jarFile.toFile())) {
      return AgentJarIndex.readIndex(jar);
    }
  }

  private static void createFile(Path root, String relativePath) throws IOException {
    Path file = root.resolve(relativePath.replace('/', java.io.File.separatorChar));
    Files.createDirectories(file.getParent());
    Files.createFile(file);
  }

  private static Path writeIndex(Path resourcesDir, Path indexFile) throws IOException {
    AgentJarIndex.IndexGenerator generator = new AgentJarIndex.IndexGenerator(resourcesDir);
    generator.buildIndex();
    generator.writeIndex(indexFile);
    return indexFile;
  }

  private static List<String> readPrefixes(Path indexFile) throws IOException {
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(indexFile)))) {
      int prefixCount = in.readInt();
      String[] prefixes = new String[prefixCount];
      for (int i = 0; i < prefixCount; i++) {
        prefixes[i] = in.readUTF();
      }
      return Arrays.asList(prefixes);
    }
  }

  @Test
  void classEntryNameResolvesBootstrapClassToTopLevel(@TempDir Path tempDir) throws Exception {
    Path resources = tempDir.resolve("resources");
    // Only the inst/ prefix exists; datadog.* falls back to main jar (prefixId == 0)
    createFile(resources, "inst/datadog/trace/instrumentation/servlet/ServletAdvice.classdata");

    AgentJarIndex index = buildAndReadIndex(resources, tempDir);

    assertNotNull(index);
    assertEquals(
        "datadog/trace/bootstrap/Bootstrap.class",
        index.classEntryName("datadog.trace.bootstrap.Bootstrap"));
  }

  @Test
  void classEntryNameResolvesInstrumentationClassToInstPrefix(@TempDir Path tempDir)
      throws Exception {
    Path resources = tempDir.resolve("resources");
    createFile(resources, "inst/datadog/trace/instrumentation/servlet/ServletAdvice.classdata");

    AgentJarIndex index = buildAndReadIndex(resources, tempDir);

    assertNotNull(index);
    assertEquals(
        "inst/datadog/trace/instrumentation/servlet/ServletAdvice.classdata",
        index.classEntryName("datadog.trace.instrumentation.servlet.ServletAdvice"));
  }

  @Test
  void classEntryNameReturnsNullForUnknownPackage(@TempDir Path tempDir) throws Exception {
    Path resources = tempDir.resolve("resources");
    createFile(resources, "inst/datadog/trace/instrumentation/servlet/ServletAdvice.classdata");

    AgentJarIndex index = buildAndReadIndex(resources, tempDir);

    assertNotNull(index);
    // com.example.* is not indexed → null
    assertNull(index.classEntryName("com.example.Unknown"));
  }

  @Test
  void classEntryNameResolvesDeepNestedClassToCorrectPrefix(@TempDir Path tempDir)
      throws Exception {
    Path resources = tempDir.resolve("resources");
    createFile(resources, "metrics/com/datadoghq/stats/StatsClient.classdata");

    AgentJarIndex index = buildAndReadIndex(resources, tempDir);

    assertNotNull(index);
    assertEquals(
        "metrics/com/datadoghq/stats/StatsClient.classdata",
        index.classEntryName("com.datadoghq.stats.StatsClient"));
  }

  @Test
  void resourceEntryNamePassesThroughTopLevelResources(@TempDir Path tempDir) throws Exception {
    Path resources = tempDir.resolve("resources");
    createFile(resources, "inst/datadog/trace/instrumentation/servlet/ServletAdvice.classdata");

    AgentJarIndex index = buildAndReadIndex(resources, tempDir);

    assertNotNull(index);
    String resourceName = "datadog/trace/bootstrap/Bootstrap.class";
    assertEquals(resourceName, index.resourceEntryName(resourceName));
  }

  @Test
  void resourceEntryNameAppendsDataSuffixForClassResource(@TempDir Path tempDir) throws Exception {
    Path resources = tempDir.resolve("resources");
    createFile(resources, "inst/datadog/trace/instrumentation/servlet/ServletAdvice.classdata");

    AgentJarIndex index = buildAndReadIndex(resources, tempDir);

    assertNotNull(index);
    assertEquals(
        "inst/datadog/trace/instrumentation/servlet/ServletAdvice.classdata",
        index.resourceEntryName("datadog/trace/instrumentation/servlet/ServletAdvice.class"));
  }

  @Test
  void multiplePrefixesAreIndexedIndependently(@TempDir Path tempDir) throws Exception {
    Path resources = tempDir.resolve("resources");
    createFile(resources, "inst/datadog/trace/instrumentation/servlet/ServletAdvice.classdata");
    createFile(resources, "metrics/com/datadoghq/stats/StatsClient.classdata");

    AgentJarIndex index = buildAndReadIndex(resources, tempDir);

    assertNotNull(index);
    assertEquals(
        "inst/datadog/trace/instrumentation/servlet/ServletAdvice.classdata",
        index.classEntryName("datadog.trace.instrumentation.servlet.ServletAdvice"));
    assertEquals(
        "metrics/com/datadoghq/stats/StatsClient.classdata",
        index.classEntryName("com.datadoghq.stats.StatsClient"));
  }

  @Test
  void buildIndexWritesPrefixesInSortedOrder(@TempDir Path tempDir) throws Exception {
    Path resources = tempDir.resolve("resources");
    createFile(resources, "metrics/com/datadoghq/stats/StatsClient.classdata");
    createFile(resources, "inst/datadog/trace/instrumentation/servlet/ServletAdvice.classdata");
    createFile(resources, "appsec/com/datadog/appsec/Event.classdata");

    Path indexFile = writeIndex(resources, tempDir.resolve("dd-java-agent.index"));

    assertEquals(Arrays.asList("appsec/", "inst/", "metrics/"), readPrefixes(indexFile));
  }

  @Test
  void buildIndexIsIndependentOfDirectoryCreationOrder(@TempDir Path tempDir) throws Exception {
    Path buildResources = tempDir.resolve("build-resources");
    createFile(buildResources, "appsec/com/datadog/appsec/Event.classdata");
    createFile(buildResources, "ci-visibility/com/datadog/ci/Visibility.classdata");
    createFile(buildResources, "cws-tls/com/datadog/cws/Tls.classdata");
    createFile(
        buildResources, "inst/datadog/trace/instrumentation/servlet/ServletAdvice.classdata");

    Path deployResources = tempDir.resolve("deploy-resources");
    createFile(deployResources, "cws-tls/com/datadog/cws/Tls.classdata");
    createFile(deployResources, "ci-visibility/com/datadog/ci/Visibility.classdata");
    createFile(deployResources, "appsec/com/datadog/appsec/Event.classdata");
    createFile(
        deployResources, "inst/datadog/trace/instrumentation/servlet/ServletAdvice.classdata");

    Path buildIndex = writeIndex(buildResources, tempDir.resolve("build-dd-java-agent.index"));
    Path deployIndex = writeIndex(deployResources, tempDir.resolve("deploy-dd-java-agent.index"));

    assertArrayEquals(Files.readAllBytes(buildIndex), Files.readAllBytes(deployIndex));
  }

  @Test
  void buildIndexWithEmptyResourcesDirProducesWorkingIndex(@TempDir Path tempDir) throws Exception {
    Path resources = tempDir.resolve("resources");
    Files.createDirectories(resources);

    AgentJarIndex index = buildAndReadIndex(resources, tempDir);

    assertNotNull(index);
    // All lookups fall through to main jar (prefixId == 0) or null
    String name = "datadog/trace/bootstrap/Bootstrap.class";
    assertEquals(name, index.resourceEntryName(name));
  }

  @Test
  void resourceEntryNameReturnsNullForResourceOutsideAnyIndexedPrefix(@TempDir Path tempDir)
      throws Exception {
    Path resources = tempDir.resolve("resources");
    createFile(resources, "inst/datadog/trace/instrumentation/servlet/ServletAdvice.classdata");

    AgentJarIndex index = buildAndReadIndex(resources, tempDir);

    assertNotNull(index);
    // com.* is not indexed → null (symmetric with classEntryNameReturnsNullForUnknownPackage)
    assertNull(index.resourceEntryName("com/example/Foo.class"));
  }

  @Test
  void resourceEntryNameDoesNotAppendDataSuffixForNonClassResources(@TempDir Path tempDir)
      throws Exception {
    Path resources = tempDir.resolve("resources");
    createFile(resources, "inst/datadog/trace/instrumentation/servlet/ServletAdvice.classdata");

    AgentJarIndex index = buildAndReadIndex(resources, tempDir);

    assertNotNull(index);
    // A non-.class resource under an indexed prefix is returned with the prefix prepended
    // but without the "data" suffix (only .class resources get "data" appended)
    assertEquals(
        "inst/datadog/trace/instrumentation/servlet/services",
        index.resourceEntryName("datadog/trace/instrumentation/servlet/services"));
  }
}
