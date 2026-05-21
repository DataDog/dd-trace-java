package datadog.smoketest;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.civisibility.CiVisibilitySmokeTest;
import datadog.trace.util.ComparableVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.internal.impldep.org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

public abstract class AbstractGradleTest extends CiVisibilitySmokeTest {

  protected static final String LATEST_GRADLE_VERSION = getLatestGradleVersion();

  // test resources use this instead of ".gradle" to avoid unwanted evaluation
  private static final String GRADLE_TEST_RESOURCE_EXTENSION = ".gradleTest";
  private static final String GRADLE_REGULAR_EXTENSION = ".gradle";

  private static final ComparableVersion GRADLE_9 = new ComparableVersion("9.0.0");

  @TempDir protected Path projectFolder;

  protected static final MockBackend mockBackend = new MockBackend();

  /**
   * Captured by JUnit 5 via parameter injection so methods running under a {@code @TableTest} row
   * can include the iteration label in diagnostic output without taking {@link TestInfo} as a test
   * parameter (which would conflict with TableTest's scenario-column convention).
   */
  protected TestInfo testInfo;

  @BeforeEach
  void resetMockBackend(TestInfo testInfo) {
    this.testInfo = testInfo;
    mockBackend.reset();
  }

  @AfterAll
  static void closeMockBackend() throws Exception {
    mockBackend.close();
  }

  private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{(.+?)\\}");

  protected void givenGradleProjectFiles(String projectFilesSources) throws IOException {
    givenGradleProjectFiles(projectFilesSources, Collections.emptyMap());
  }

  protected void givenGradleProjectFiles(
      String projectFilesSources, Map<String, Map<String, String>> replacementsByFileName)
      throws IOException {
    Path projectResourcesPath;
    try {
      projectResourcesPath =
          Paths.get(this.getClass().getClassLoader().getResource(projectFilesSources).toURI());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    FileUtils.copyDirectory(projectResourcesPath.toFile(), projectFolder.toFile());

    Files.walkFileTree(
        projectFolder,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Map<String, String> replacements =
                replacementsByFileName.get(file.getFileName().toString());
            if (replacements != null) {
              String fileContents = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
              Matcher matcher = PLACEHOLDER_PATTERN.matcher(fileContents);

              StringBuffer result = new StringBuffer();
              while (matcher.find()) {
                String propertyName = matcher.group(1);
                String replacement = replacements.getOrDefault(propertyName, matcher.group(0));
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
              }
              matcher.appendTail(result);

              Files.write(file, result.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (file.toString().endsWith(GRADLE_TEST_RESOURCE_EXTENSION)) {
              Path fileWithFixedExtension =
                  Paths.get(
                      file.toString()
                          .replace(GRADLE_TEST_RESOURCE_EXTENSION, GRADLE_REGULAR_EXTENSION));
              Files.move(file, fileWithFixedExtension);
            }

            return FileVisitResult.CONTINUE;
          }
        });

    // creating empty .git directory so that the tracer could detect projectFolder as repo root
    Files.createDirectory(projectFolder.resolve(".git"));
  }

  protected void givenGradleVersionIsCompatibleWithCurrentJvm(String gradleVersion) {
    Assumptions.assumeTrue(
        isSupported(new ComparableVersion(gradleVersion)),
        "Current JVM does not support Gradle version " + gradleVersion);
  }

  private static boolean isSupported(ComparableVersion gradleVersion) {
    // https://docs.gradle.org/current/userguide/compatibility.html
    if (JavaVirtualMachine.isJavaVersionAtLeast(26)) {
      return gradleVersion.compareTo(new ComparableVersion("9.4")) >= 0;
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(25)) {
      return gradleVersion.compareTo(new ComparableVersion("9.1")) >= 0;
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(24)) {
      return gradleVersion.compareTo(new ComparableVersion("8.14")) >= 0;
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(21)) {
      return gradleVersion.compareTo(new ComparableVersion("8.4")) >= 0;
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(20)) {
      return gradleVersion.compareTo(new ComparableVersion("8.1")) >= 0;
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(19)) {
      return gradleVersion.compareTo(new ComparableVersion("7.6")) >= 0;
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(18)) {
      return gradleVersion.compareTo(new ComparableVersion("7.5")) >= 0;
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(17)) {
      return gradleVersion.compareTo(new ComparableVersion("7.3")) >= 0;
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(16)) {
      return isWithin(gradleVersion, new ComparableVersion("7.0"), GRADLE_9);
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(15)) {
      return isWithin(gradleVersion, new ComparableVersion("6.7"), GRADLE_9);
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(14)) {
      return isWithin(gradleVersion, new ComparableVersion("6.3"), GRADLE_9);
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(13)) {
      return isWithin(gradleVersion, new ComparableVersion("6.0"), GRADLE_9);
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(12)) {
      return isWithin(gradleVersion, new ComparableVersion("5.4"), GRADLE_9);
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(11)) {
      return isWithin(gradleVersion, new ComparableVersion("5.0"), GRADLE_9);
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(10)) {
      return isWithin(gradleVersion, new ComparableVersion("4.7"), GRADLE_9);
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(9)) {
      return isWithin(gradleVersion, new ComparableVersion("4.3"), GRADLE_9);
    } else if (JavaVirtualMachine.isJavaVersionAtLeast(8)) {
      return isWithin(gradleVersion, new ComparableVersion("2.0"), GRADLE_9);
    }
    return false;
  }

  private static boolean isWithin(
      ComparableVersion version,
      ComparableVersion lowerInclusive,
      ComparableVersion upperExclusive) {
    return version.compareTo(lowerInclusive) >= 0 && version.compareTo(upperExclusive) < 0;
  }

  protected void givenConfigurationCacheIsCompatibleWithCurrentPlatform(
      boolean configurationCacheEnabled) {
    if (configurationCacheEnabled) {
      Assumptions.assumeFalse(
          JavaVirtualMachine.isIbm8(), "Configuration cache is not compatible with IBM 8");
    }
  }

  private static String getLatestGradleVersion() {
    Properties properties = new Properties();
    try (InputStream stream =
        AbstractGradleTest.class
            .getClassLoader()
            .getResourceAsStream("latest-tool-versions.properties")) {
      if (stream == null) {
        throw new IllegalStateException(
            "Could not find latest-tool-versions.properties on classpath");
      }
      properties.load(stream);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return properties.getProperty("gradle.version");
  }
}
