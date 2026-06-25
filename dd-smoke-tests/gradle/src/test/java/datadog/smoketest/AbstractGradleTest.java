package datadog.smoketest;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.gradle.internal.impldep.org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractGradleTest extends CiVisibilitySmokeTest {

  private static final Properties TOOL_VERSIONS = loadToolVersions();
  protected static final String LATEST_GRADLE_VERSION = toolVersion("gradle.latest");

  // test resources use this instead of ".gradle" to avoid unwanted evaluation
  private static final String GRADLE_TEST_RESOURCE_EXTENSION = ".gradleTest";
  private static final String GRADLE_REGULAR_EXTENSION = ".gradle";

  private static final ComparableVersion GRADLE_9 = new ComparableVersion("9.0.0");

  // Gradle daemons may keep file handles on their temp directory open for a short while after being
  // stopped; retry a few times to give them a chance to release before giving up.
  private static final int TEMP_DIR_CLEANUP_RETRIES = 10;
  private static final long TEMP_DIR_CLEANUP_RETRY_DELAY_MILLIS = 200;

  @TempDir protected Path projectFolder;

  protected final MockBackend mockBackend = new MockBackend();

  @BeforeEach
  void resetMockBackend() {
    mockBackend.reset();
  }

  @AfterAll
  void closeMockBackend() throws Exception {
    mockBackend.close();
  }

  /**
   * Recursively deletes a directory that a Gradle daemon has been writing into, on a best-effort
   * basis. We delete it ourselves (rather than letting JUnit's {@code @TempDir} cleanup do it at
   * class teardown) because a daemon may not have released its file handles on {@code
   * caches/<version>} by the time the recursive delete runs, which makes the delete fail with a
   * {@link java.nio.file.DirectoryNotEmptyException}.
   */
  protected static void deleteTempDirectoryQuietly(Path directory) {
    if (directory == null) {
      return;
    }
    for (int attempt = 0;
        attempt < TEMP_DIR_CLEANUP_RETRIES && Files.exists(directory);
        attempt++) {
      FileUtils.deleteQuietly(directory.toFile());
      if (!Files.exists(directory)) {
        return;
      }
      try {
        Thread.sleep(TEMP_DIR_CLEANUP_RETRY_DELAY_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    if (Files.exists(directory)) {
      System.err.println(
          "WARNING: could not fully delete temp directory "
              + directory
              + " after stopping Gradle daemons; leaving it for the OS to reap. "
              + "A Gradle daemon likely still holds a file handle on it.");
    }
  }

  /** Kills the Gradle daemons whose logs live under {@code testKitDir}, on a best-effort basis. */
  protected static void killGradleDaemonsIn(Path testKitDir) {
    if (testKitDir == null || !Files.exists(testKitDir)) {
      return;
    }
    boolean windows = OperatingSystem.isWindows();
    try (Stream<Path> files = Files.walk(testKitDir)) {
      files
          .filter(Files::isRegularFile)
          .forEach(
              file -> {
                String name = file.getFileName().toString();
                if (!name.startsWith("daemon-") || !name.endsWith(".out.log")) {
                  return;
                }
                String pid =
                    name.substring("daemon-".length(), name.length() - ".out.log".length());
                if (!pid.matches("\\d+")) {
                  // skip the UUID fallback Gradle uses when the PID is unavailable
                  return;
                }
                ProcessBuilder kill =
                    windows
                        ? new ProcessBuilder("taskkill", "/F", "/PID", pid)
                        : new ProcessBuilder("kill", pid);
                try {
                  kill.redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } catch (Exception e) {
                  // best effort — the daemon may already be stopped
                }
              });
    } catch (Exception e) {
      // best effort — failing to enumerate daemon logs must not fail the test run
    }
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

  private static Properties loadToolVersions() {
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
    return properties;
  }

  protected static String toolVersion(String key) {
    String value = TOOL_VERSIONS.getProperty(key);
    if (value == null) {
      throw new IllegalStateException(
          "Missing '"
              + key
              + "' in latest-tool-versions.properties; re-run the "
              + "update-smoke-test-latest-versions workflow.");
    }
    return value;
  }
}
