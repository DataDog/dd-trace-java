package datadog.telemetry.dependency;

import static datadog.telemetry.dependency.DependencyTestHelper.getJar;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

class DependencyResolverTest {

  @TempDir File testDir;

  @TableTest({
    "scenario                                    | bundleSymbolicName                          | bundleName                      | bundleVersion              | implementationTitle               | implementationVersion | filename                           | expectedName                                | expectedVersion    ",
    "agrona                                      | 'org.agrona.core'                           | 'org.agrona.core'               | '1.7.2'                    | 'Agrona'                          | '1.7.2'               | 'agrona-1.7.2.jar'                 | 'agrona'                                    | '1.7.2'            ",
    "asm-util                                    | 'org.objectweb.asm.util'                    | 'org.objectweb.asm.util'        | '9.2.0'                    | 'Utilities for ASM'               | '9.2'                 | 'asm-util-9.2.jar'                 | 'asm-util'                                  | '9.2'              ",
    "bson                                        | 'org.mongodb.bson'                          | 'bson'                          | '4.2.0'                    |                                   |                       | 'bson-4.2.0.jar'                   | 'org.mongodb:bson'                          | '4.2.0'            ",
    "bson4jackson                                | 'de.undercouch.bson4jackson'                | 'bson4jackson'                  | '2.11.0'                   |                                   |                       | 'bson4jackson-2.11.0.jar'          | 'de.undercouch:bson4jackson'                | '2.11.0'           ",
    "caffeine                                    | 'com.github.ben-manes.caffeine'             | 'com.github.ben-manes.caffeine' | '2.8.5'                    |                                   |                       | 'caffeine-2.8.5.jar'               | 'com.github.ben-manes:caffeine'             | '2.8.5'            ",
    "commons logging                             | 'org.apache.commons.logging'                | 'Apache Commons Logging'        | '1.2.0'                    | 'Apache Commons Logging'          | '1.2'                 | 'commons-logging-1.2.jar'          | 'commons-logging'                           | '1.2'              ",
    "freemarker                                  | 'org.freemarker.freemarker'                 | 'org.freemarker.freemarker'     | '2.3.27.stable-incubating' | 'FreeMarker'                      | '2.3.27'              | 'freemarker-2.3.27-incubating.jar' | 'org.freemarker:freemarker'                 | '2.3.27-incubating'",
    "groovy with implementation version          | 'groovy'                                    | 'Groovy Runtime'                | '2.4.12'                   |                                   |                       | 'groovy-2.4.12.jar'                | 'groovy'                                    | '2.4.12'           ",
    "groovy without version in filename          | 'groovy'                                    | 'Groovy Runtime'                | '2.4.12'                   |                                   |                       | 'groovy.jar'                       | 'groovy'                                    | ''                 ",
    "hsqldb                                      | 'org.hsqldb.hsqldb'                         | 'HSQLDB'                        | '2.3.5'                    | 'Standard runtime'                | '2.3.5'               | 'hsqldb-2.3.5-jdk6debug.jar'       | 'org.hsqldb:hsqldb'                         | '2.3.5-jdk6debug'  ",
    "jakarta inject                              | 'org.glassfish.hk2.external.jakarta.inject' | 'javax.inject:1 as OSGi bundle' | '2.6.1'                    |                                   |                       | 'jakarta.inject-2.6.1.jar'         | 'org.glassfish.hk2.external:jakarta.inject' | '2.6.1'            ",
    "junit with implementation version           |                                             |                                 |                            | 'JUnit'                           | '4.12'                | 'junit-4.12.jar'                   | 'junit'                                     | '4.12'             ",
    "junit without version in filename           |                                             |                                 |                            | 'JUnit'                           | '4.12'                | 'junit.jar'                        | 'junit'                                     | ''                 ",
    "multiverse-core with version in filename    |                                             |                                 |                            |                                   |                       | 'multiverse-core-0.7.0.jar'        | 'multiverse-core'                           | '0.7.0'            ",
    "multiverse-core without version in filename |                                             |                                 |                            |                                   |                       | 'multiverse-core.jar'              | 'multiverse-core'                           | ''                 ",
    "spring webmvc                               | 'org.springframework.web.servlet'           | 'Spring Web Servlet'            | '3.0.0.RELEASE'            | 'org.springframework.web.servlet' | '3.0.0.RELEASE'       | 'spring-webmvc.jar'                | 'spring-webmvc'                             | '3.0.0.RELEASE'    ",
    "opencsv                                     | 'com.opencsv'                               | 'opencsv'                       | '4.1.0'                    |                                   |                       | 'opencsv-4.1.jar'                  | 'opencsv'                                   | '4.1'              ",
    "liquibase core                              | 'org.liquibase.core'                        | 'liquibase-core'                | '0.0.0.SNAPSHOT'           |                                   |                       | 'liquibase-core-4.6.2.jar'         | 'liquibase-core'                            | '4.6.2'            ",
    "roaring bitmap                              | 'org.roaringbitmap.RoaringBitmap'           | 'RoaringBitmap'                 | '0.0.1'                    |                                   |                       | 'RoaringBitmap-0.0.1.jar'          | 'org.roaringbitmap:RoaringBitmap'           | '0.0.1'            ",
    "jmustache                                   | 'com.samskivert.jmustache'                  | 'jmustache'                     | '1.14.0'                   |                                   |                       | 'jmustache-1.14.jar'               | 'com.samskivert:jmustache'                  | '1.14'             "
  })
  @ParameterizedTest(name = "resolve from manifest and filename fallbacks [{index}] {0}")
  void resolveFromManifestAndFilenameFallbacks(
      String bundleSymbolicName,
      String bundleName,
      String bundleVersion,
      String implementationTitle,
      String implementationVersion,
      String filename,
      String expectedName,
      String expectedVersion)
      throws IOException {
    File file =
        prepareJar(
            filename,
            bundleSymbolicName,
            bundleName,
            bundleVersion,
            implementationTitle,
            implementationVersion);

    List<Dependency> dependencies = DependencyResolver.resolve(file.toURI());

    assertEquals(1, dependencies.size());
    Dependency dependency = dependencies.get(0);
    assertEquals(expectedName, dependency.name);
    assertEquals(expectedVersion, dependency.version);
    assertEquals(filename, dependency.source);
    assertNotNull(dependency.hash);
    assertFalse(dependency.hash.isEmpty());
  }

  private File prepareJar(
      String filename,
      String bundleSymbolicName,
      String bundleName,
      String bundleVersion,
      String implementationTitle,
      String implementationVersion)
      throws IOException {
    File file = new File(testDir, filename);
    StringBuilder manifest = new StringBuilder();
    appendManifestLine(manifest, "Bundle-SymbolicName", bundleSymbolicName);
    appendManifestLine(manifest, "Bundle-Name", bundleName);
    appendManifestLine(manifest, "Bundle-Version", bundleVersion);
    appendManifestLine(manifest, "Implementation-Title", implementationTitle);
    appendManifestLine(manifest, "Implementation-Version", implementationVersion);
    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
      out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
      out.write(manifest.toString().getBytes(UTF_8));
      out.closeEntry();
    }
    return file;
  }

  private static void appendManifestLine(StringBuilder manifest, String key, String value) {
    if (value == null) {
      return;
    }
    if (manifest.length() > 0) {
      manifest.append('\n');
    }
    manifest.append(key).append(": ").append(value);
  }

  @TableTest({
    "scenario     | jar                       | expectedName                 | expectedHash                               | expectedVersion",
    "bson4jackson | 'bson4jackson-2.11.0.jar' | 'de.undercouch:bson4jackson' | '428A23E33D19DACD6E04CA7DD746206849861A95' | '2.11.0'       ",
    "bson         | 'bson-4.2.0.jar'          | 'org.mongodb:bson'           | 'F87C3A90DA4BB1DA6D3A73CA18004545AD2EF06A' | '4.2.0'        "
  })
  @ParameterizedTest(name = "jar without pom.properties get resolved with hash [{index}] {0}")
  void jarWithoutPomPropertiesGetResolvedWithHash(
      String jar, String expectedName, String expectedHash, String expectedVersion)
      throws IOException {
    knownJarCheck(jar, expectedName, expectedVersion, expectedHash);
  }

  @Test
  void jarWithPomPropertiesIsResolved() throws IOException {
    knownJarCheck("commons-logging-1.2.jar", "commons-logging:commons-logging", "1.2", null);
  }

  @Test
  void jarWithDdJavaAgentPomPropertiesResolvesToComDatadoghqDdJavaAgent() throws IOException {
    // a jar containing META-INF/maven/com.datadoghq/dd-java-agent/pom.properties
    File file = new File(testDir, "dd-java-agent.jar");
    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
      out.putNextEntry(new ZipEntry("META-INF/maven/com.datadoghq/dd-java-agent/pom.properties"));
      out.write("groupId=com.datadoghq\nartifactId=dd-java-agent\nversion=1.0.0\n".getBytes(UTF_8));
      out.closeEntry();
    }

    List<Dependency> deps = DependencyResolver.resolve(file.toURI());

    assertEquals(1, deps.size());
    assertEquals("com.datadoghq:dd-java-agent", deps.get(0).name);
    assertEquals("1.0.0", deps.get(0).version);
    assertNull(deps.get(0).hash);
  }

  @Test
  void jarWithoutManifestAndNoVersionInFilenameGetsResolved() throws IOException {
    knownJarCheck(
        "groovy-no-manifest-info.jar",
        "groovy-no-manifest-info",
        "",
        "1C1C8E5547A54F593B97584D45F3636F479B9498");
  }

  @Test
  void tryToDetermineLibName() throws IOException {
    File temp = File.createTempFile("temp", ".zip");
    try {
      assertTrue(DependencyResolver.resolve(temp.toURI()).isEmpty());
    } finally {
      temp.delete();
    }
  }

  @Test
  void tryToDetermineNonExistingLibName() throws IOException {
    File temp = File.createTempFile("temp", ".zip");
    temp.delete();

    assertTrue(DependencyResolver.resolve(temp.toURI()).isEmpty());
  }

  @Test
  void tryToDetermineInvalidJarLib() throws IOException {
    File temp = File.createTempFile("temp", ".jar");
    try (FileOutputStream out = new FileOutputStream(temp)) {
      out.write("just a text file".getBytes(UTF_8));
    }

    assertTrue(DependencyResolver.resolve(temp.toURI()).isEmpty());
  }

  @Test
  void springBootDependency() throws URISyntaxException {
    URI zipPath = zipPath("datadog/telemetry/dependencies/spring-boot-app.jar");
    URI uri = new URI("jar:" + zipPath + "!/BOOT-INF/lib/opentracing-util-0.33.0.jar!/");

    Dependency dep = DependencyResolver.resolve(uri).get(0);

    assertNotNull(dep);
    assertEquals("io.opentracing:opentracing-util", dep.name);
    assertEquals("0.33.0", dep.version);
    assertNull(dep.hash);
    assertEquals("opentracing-util-0.33.0.jar", dep.source);
  }

  @Test
  void springBootDependencyWithoutTrailingSlash() throws URISyntaxException {
    URI zipPath = zipPath("datadog/telemetry/dependencies/spring-boot-app.jar");
    URI uri = new URI("jar:" + zipPath + "!/BOOT-INF/lib/opentracing-util-0.33.0.jar!");

    Dependency dep = DependencyResolver.resolve(uri).get(0);

    assertNotNull(dep);
    assertEquals("io.opentracing:opentracing-util", dep.name);
    assertEquals("0.33.0", dep.version);
    assertNull(dep.hash);
    assertEquals("opentracing-util-0.33.0.jar", dep.source);
  }

  @Test
  void springBootDependencyNewStyle() throws URISyntaxException {
    String zipPath =
        zipPath("datadog/telemetry/dependencies/spring-boot-app.jar")
            .toString()
            .replace("file:", "nested:");
    URI uri = new URI("jar:" + zipPath + "/!BOOT-INF/lib/opentracing-util-0.33.0.jar!/");

    Dependency dep = DependencyResolver.resolve(uri).get(0);

    assertNotNull(dep);
    assertEquals("io.opentracing:opentracing-util", dep.name);
    assertEquals("0.33.0", dep.version);
    assertNull(dep.hash);
    assertEquals("opentracing-util-0.33.0.jar", dep.source);
  }

  @Test
  void springBootDependencyNewStyleEmptyPath() throws URISyntaxException {
    URI uri = new URI("jar:nested:");

    List<Dependency> deps = DependencyResolver.resolve(uri);

    assertTrue(deps.isEmpty());
  }

  @Test
  void springBootDependencyOldStyleEmptyPath() throws URISyntaxException {
    URI uri = new URI("jar:file:");

    List<Dependency> deps = DependencyResolver.resolve(uri);

    assertTrue(deps.isEmpty());
  }

  @Test
  void jarUnknown() throws URISyntaxException {
    URI uri = new URI("jar:unknown");

    List<Dependency> deps = DependencyResolver.resolve(uri);

    assertTrue(deps.isEmpty());
  }

  @Test
  void springBootDependencyWithoutMavenMetadata() throws IOException, URISyntaxException {
    ByteArrayOutputStream innerJarData = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(innerJarData)) {
      out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
      out.closeEntry();
    }

    File file = new File(testDir, "app.jar");
    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
      out.putNextEntry(new ZipEntry("BOOT-INF/lib/lib-1.0.jar"));
      out.write(innerJarData.toByteArray());
      out.closeEntry();
    }

    URI uri = new URI("jar:file:" + file.getAbsolutePath() + "!/BOOT-INF/lib/lib-1.0.jar!/");
    List<Dependency> deps = DependencyResolver.resolve(uri);

    assertEquals(1, deps.size());
    assertEquals("lib-1.0.jar", deps.get(0).source);
    assertNotNull(deps.get(0).hash);
  }

  @Test
  void fatJarWithMultiplePomProperties() throws URISyntaxException {
    URI uri = zipPath("datadog/telemetry/dependencies/budgetapp.jar");

    List<Dependency> deps = DependencyResolver.resolve(uri);

    assertEquals(105, deps.size());
    for (Dependency dep : deps) {
      assertNull(dep.hash);
    }
  }

  @Test
  void fatJarWithTwoPomProperties() throws URISyntaxException {
    URI uri = zipPath("datadog/telemetry/dependencies/budgetappreduced.jar");

    List<Dependency> deps = DependencyResolver.resolve(uri);
    Dependency dep1 = deps.get(0);
    Dependency dep2 = deps.get(1);

    assertEquals(2, deps.size());
    assertTrue(dep1.name.equals("org.yaml:snakeyaml") || dep2.name.equals("org.yaml:snakeyaml"));
    for (Dependency dep : deps) {
      assertNull(dep.hash);
    }
  }

  @Test
  void fatJarWithTwoPomPropertiesOneOfThemBad() throws URISyntaxException {
    URI uri = zipPath("datadog/telemetry/dependencies/budgetappreducedbadproperties.jar");

    List<Dependency> deps = DependencyResolver.resolve(uri);
    Dependency dep1 = deps.get(0);

    assertEquals(1, deps.size());
    assertEquals("org.yaml:snakeyaml", dep1.name);
    for (Dependency dep : deps) {
      assertNull(dep.hash);
    }
  }

  @Test
  void invalidPomPropertiesResultsInFallback() throws IOException {
    // this jar has an invalid pom.properties and it should be resolved with its file name
    knownJarCheck(
        "invalidpomproperties.jar",
        "invalidpomproperties",
        "",
        "6438819DAB9C9AC18D8A6922C8A923C2ADAEA85D");
  }

  @Test
  void attemptToExtractDependenciesFromDirectory() throws IOException {
    File dir = new File(testDir, "dir");
    dir.mkdirs();

    List<Dependency> deps = DependencyResolver.resolve(dir.toURI());

    assertTrue(deps.isEmpty());

    // resolve without catching exceptions: it does not throw
    deps = DependencyResolver.internalResolve(dir.toURI());

    assertTrue(deps.isEmpty());
  }

  @Test
  void attemptToExtractDependenciesFromDirectoryWithinJar() throws IOException {
    File file = new File(testDir, "app.jar");
    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
      out.putNextEntry(new ZipEntry("classes/"));
      out.closeEntry();
    }

    URI uri = URI.create("jar:file:" + file.getAbsolutePath() + "!/classes!/");
    List<Dependency> deps = DependencyResolver.resolve(uri);

    assertTrue(deps.isEmpty());

    // resolve without catching exceptions: it does not throw
    deps = DependencyResolver.internalResolve(uri);

    assertTrue(deps.isEmpty());
  }

  private static void knownJarCheck(
      String jarName, String expectedName, String expectedVersion, String expectedHash) {
    File jarFile = getJar(jarName);
    List<Dependency> deps = DependencyResolver.resolve(jarFile.toURI());

    assertEquals(1, deps.size());
    Dependency dep = deps.get(0);
    assertNotNull(dep);
    assertEquals(jarName, dep.source);
    assertEquals(expectedName, dep.name);
    assertEquals(expectedVersion, dep.version);
    assertEquals(expectedHash, dep.hash);
  }

  @Test
  void jbossMayUseTheJarFileFormatToReferenceJarFilesInsteadOfNestedJars() throws IOException {
    File file = prepareJar("junit-4.12.jar", null, null, null, "JUnit", "4.12");
    URI uri = URI.create("jar:" + file.toURI() + "!/");

    List<Dependency> deps = DependencyResolver.resolve(uri);

    assertEquals(1, deps.size());
  }

  private static URI zipPath(String outerJar) throws URISyntaxException {
    URL outerJarUrl = Thread.currentThread().getContextClassLoader().getResource(outerJar);

    assertNotNull(outerJarUrl, "Resource not found: " + outerJar);

    return outerJarUrl.toURI();
  }
}
