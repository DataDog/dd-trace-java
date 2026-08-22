package datadog.smoke;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HelloPluginFunctionalTest {

  @TempDir
  Path testProjectDir;
  private File buildFile;

  @BeforeEach
  void setUp() throws IOException {
    buildFile = testProjectDir.resolve("build.gradle").toFile();
    Files.write(
        buildFile.toPath(),
        "plugins { id 'datadog.smoke.helloplugin' }".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void pluginPrintsHelloMessageOnGradle85() {
    String gradleInstallationDir =
        requireNonNull(System.getenv("GRADLE_INSTALLATION_DIR"), "GRADLE_INSTALLATION_DIR");
    BuildResult result =
        GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withPluginClasspath()
            .withGradleInstallation(new File(gradleInstallationDir))
            .withArguments("hello", "--stacktrace")
            .withEnvironment(sanitizedGradleEnvironment(testProjectDir.resolve("gradle-user-home")))
            .forwardOutput()
            .build();

    assertTrue(result.getOutput().contains("Hello from my plugin!"));
  }

  private static Map<String, String> sanitizedGradleEnvironment(Path gradleUserHomeDir) {
    Map<String, String> environment = new HashMap<>(System.getenv());
    environment.put("GRADLE_ARGS", "");
    environment.put("GRADLE_OPTS", "");
    environment.put("GRADLE_USER_HOME", gradleUserHomeDir.toString());
    return environment;
  }
}
