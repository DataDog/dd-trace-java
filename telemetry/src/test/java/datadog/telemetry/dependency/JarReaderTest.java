package datadog.telemetry.dependency;

import static datadog.telemetry.dependency.DependencyTestHelper.getJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class JarReaderTest {

  @Test
  void readPlainJarWithManifestAndNoPomProperties() throws IOException {
    String jarPath = getJar("bson-4.2.0.jar").getAbsolutePath();

    JarReader.Extracted result = JarReader.readJarFile(jarPath);

    assertEquals("bson-4.2.0.jar", result.jarName);
    assertTrue(result.pomProperties.isEmpty());
    assertNotNull(result.manifest);
    assertEquals("bson", result.manifest.getValue("Bundle-Name"));
  }

  @Test
  void readJarWithoutManifest() throws IOException {
    String jarPath = getJar("groovy-no-manifest-info.jar").getAbsolutePath();

    JarReader.Extracted result = JarReader.readJarFile(jarPath);

    assertEquals("groovy-no-manifest-info.jar", result.jarName);
    assertTrue(result.pomProperties.isEmpty());
    assertNotNull(result.manifest);
    assertTrue(result.manifest.isEmpty());
  }

  @Test
  void readPlainJarWithManifestAndPomProperties() throws IOException {
    String jarPath = getJar("commons-logging-1.2.jar").getAbsolutePath();

    JarReader.Extracted result = JarReader.readJarFile(jarPath);

    assertEquals("commons-logging-1.2.jar", result.jarName);
    assertEquals(1, result.pomProperties.size());
    Properties properties =
        result.pomProperties.get("META-INF/maven/commons-logging/commons-logging/pom.properties");
    assertNotNull(properties);
    assertEquals("commons-logging", properties.getProperty("groupId"));
    assertEquals("commons-logging", properties.getProperty("artifactId"));
    assertEquals("1.2", properties.getProperty("version"));
    assertNotNull(result.manifest);
    assertEquals("Apache Commons Logging", result.manifest.getValue("Bundle-Name"));
  }

  @Test
  void readNestedJar() throws IOException {
    String outerPath = getJar("spring-boot-app.jar").getAbsolutePath();

    JarReader.Extracted result =
        JarReader.readNestedJarFile(outerPath, "BOOT-INF/lib/opentracing-util-0.33.0.jar");

    assertEquals("opentracing-util-0.33.0.jar", result.jarName);
    assertEquals(1, result.pomProperties.size());
    Properties properties =
        result.pomProperties.get("META-INF/maven/io.opentracing/opentracing-util/pom.properties");
    assertNotNull(properties);
    assertEquals("io.opentracing", properties.getProperty("groupId"));
    assertEquals("opentracing-util", properties.getProperty("artifactId"));
    assertEquals("0.33.0", properties.getProperty("version"));
    assertNotNull(result.manifest);
    assertEquals("io.opentracing.util", result.manifest.getValue("Automatic-Module-Name"));
  }

  @Test
  void nonExistentSimpleJar() {
    assertThrows(IOException.class, () -> JarReader.readJarFile("non-existent.jar"));
  }

  @Test
  void nonExistentOuterJarForNestedJar() {
    assertThrows(
        IOException.class,
        () ->
            JarReader.readNestedJarFile(
                "non-existent.jar", "BOOT-INF/lib/opentracing-util-0.33.0.jar"));
  }

  @Test
  void nonExistentInnerJarForNestedJar() throws IOException {
    String outerPath = getJar("spring-boot-app.jar").getAbsolutePath();

    assertThrows(
        IOException.class,
        () -> JarReader.readNestedJarFile(outerPath, "BOOT-INF/lib/non-existent.jar"));
  }

  @Test
  void doublyNestedJarPath() {
    assertThrows(
        IOException.class,
        () ->
            JarReader.readNestedJarFile(
                "non-existent.jar", "BOOT-INF/lib/opentracing-util-0.33.0.jar/third"));
  }

  @Test
  void emptyNestedJarPath() {
    assertThrows(IOException.class, () -> JarReader.readNestedJarFile("non-existent.jar", ""));
  }
}
