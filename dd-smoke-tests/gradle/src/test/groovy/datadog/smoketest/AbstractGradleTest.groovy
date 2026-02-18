package datadog.smoketest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import datadog.environment.JavaVirtualMachine
import datadog.trace.civisibility.CiVisibilitySmokeTest
import datadog.trace.util.ComparableVersion
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.gradle.internal.impldep.org.apache.commons.io.FileUtils
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assumptions
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.TempDir
import spock.util.environment.Jvm

import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Matcher
import java.util.regex.Pattern

class AbstractGradleTest extends CiVisibilitySmokeTest {

  static final String LATEST_GRADLE_VERSION = getLatestGradleVersion()

  // test resources use this instead of ".gradle" to avoid unwanted evaluation
  private static final String GRADLE_TEST_RESOURCE_EXTENSION = ".gradleTest"
  private static final String GRADLE_REGULAR_EXTENSION = ".gradle"

  private static final ComparableVersion GRADLE_9 = new ComparableVersion("9.0.0")

  @TempDir
  protected Path projectFolder

  @Shared
  @AutoCleanup
  protected MockBackend mockBackend = new MockBackend()

  def setup() {
    mockBackend.reset()
  }

  private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile('\\$\\{(.+?)\\}')

  protected void givenGradleProjectFiles(String projectFilesSources, Map<String, Map<String, String>> replacementsByFileName = [:]) {
    def projectResourcesUri = this.getClass().getClassLoader().getResource(projectFilesSources).toURI()
    def projectResourcesPath = Paths.get(projectResourcesUri)
    FileUtils.copyDirectory(projectResourcesPath.toFile(), projectFolder.toFile())

    Files.walkFileTree(projectFolder, new SimpleFileVisitor<Path>() {
        @Override
        FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          def replacements = replacementsByFileName.get(file.getFileName().toString())
          if (replacements != null) {
            def fileContents = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(fileContents)

            StringBuffer result = new StringBuffer()
            while (matcher.find()) {
              String propertyName = matcher.group(1)
              String replacement = replacements.getOrDefault(propertyName, matcher.group(0))
              matcher.appendReplacement(result, Matcher.quoteReplacement(replacement))
            }
            matcher.appendTail(result)

            Files.write(file, result.toString().getBytes(StandardCharsets.UTF_8))
          }

          if (file.toString().endsWith(GRADLE_TEST_RESOURCE_EXTENSION)) {
            def fileWithFixedExtension = Paths.get(file.toString().replace(GRADLE_TEST_RESOURCE_EXTENSION, GRADLE_REGULAR_EXTENSION))
            Files.move(file, fileWithFixedExtension)
          }

          return FileVisitResult.CONTINUE
        }
      })

    // creating empty .git directory so that the tracer could detect projectFolder as repo root
    Files.createDirectory(projectFolder.resolve(".git"))
  }

  protected void givenGradleVersionIsCompatibleWithCurrentJvm(String gradleVersion) {
    Assumptions.assumeTrue(isSupported(new ComparableVersion(gradleVersion)),
      "Current JVM " + Jvm.current.javaVersion + " does not support Gradle version " + gradleVersion)
  }

  private static boolean isSupported(ComparableVersion gradleVersion) {
    // https://docs.gradle.org/current/userguide/compatibility.html
    if (Jvm.current.isJavaVersionCompatible(26)) {
      return gradleVersion.compareTo(new ComparableVersion("9.4")) >= 0
    } else if (Jvm.current.isJavaVersionCompatible(25)) {
      return gradleVersion.compareTo(new ComparableVersion("9.1")) >= 0
    } else if (Jvm.current.isJavaVersionCompatible(24)) {
      return gradleVersion.compareTo(new ComparableVersion("8.14")) >= 0
    } else if (Jvm.current.java21Compatible) {
      return gradleVersion.compareTo(new ComparableVersion("8.4")) >= 0
    } else if (Jvm.current.java20) {
      return gradleVersion.compareTo(new ComparableVersion("8.1")) >= 0
    } else if (Jvm.current.java19) {
      return gradleVersion.compareTo(new ComparableVersion("7.6")) >= 0
    } else if (Jvm.current.java18) {
      return gradleVersion.compareTo(new ComparableVersion("7.5")) >= 0
    } else if (Jvm.current.java17) {
      return gradleVersion.compareTo(new ComparableVersion("7.3")) >= 0
    } else if (Jvm.current.java16) {
      return gradleVersion.isWithin(new ComparableVersion("7.0"), GRADLE_9)
    } else if (Jvm.current.java15) {
      return gradleVersion.isWithin(new ComparableVersion("6.7"), GRADLE_9)
    } else if (Jvm.current.java14) {
      return gradleVersion.isWithin(new ComparableVersion("6.3"), GRADLE_9)
    } else if (Jvm.current.java13) {
      return gradleVersion.isWithin(new ComparableVersion("6.0"), GRADLE_9)
    } else if (Jvm.current.java12) {
      return gradleVersion.isWithin(new ComparableVersion("5.4"), GRADLE_9)
    } else if (Jvm.current.java11) {
      return gradleVersion.isWithin(new ComparableVersion("5.0"), GRADLE_9)
    } else if (Jvm.current.java10) {
      return gradleVersion.isWithin(new ComparableVersion("4.7"), GRADLE_9)
    } else if (Jvm.current.java9) {
      return gradleVersion.isWithin(new ComparableVersion("4.3"), GRADLE_9)
    } else if (Jvm.current.java8) {
      return gradleVersion.isWithin(new ComparableVersion("2.0"), GRADLE_9)
    }
    return false
  }

  protected void givenConfigurationCacheIsCompatibleWithCurrentPlatform(boolean configurationCacheEnabled) {
    if (configurationCacheEnabled) {
      Assumptions.assumeFalse(JavaVirtualMachine.isIbm8(), "Configuration cache is not compatible with IBM 8")
    }
  }

  private static String getLatestGradleVersion() {
    OkHttpClient client = new OkHttpClient()
    Request request = new Request.Builder().url("https://services.gradle.org/versions/current").build()
    try (Response response = client.newCall(request).execute()) {
      if (!response.successful) {
        return GradleVersion.current().version
      }
      def responseBody = response.body().string()
      ObjectMapper mapper = new ObjectMapper()
      JsonNode root = mapper.readTree(responseBody)
      return root.get("version").asText()
    }
  }
}
