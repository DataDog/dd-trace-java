package datadog.smoke;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class HelloPluginFunctionalTest {

  @TempDir
  Path testProjectDir;
  private File buildFile;

  @BeforeEach
  void setUp() throws IOException {
    buildFile = testProjectDir.resolve("build.gradle").toFile();
    Files.write(buildFile.toPath(), "plugins { id 'datadog.smoke.helloplugin' }".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void pluginPrintsHelloMessageOnGradle85() {
    BuildResult result = GradleRunner.create()
        .withProjectDir(testProjectDir.toFile())
        .withPluginClasspath()
        // Use the same Gradle version as of the actual smoke test that builds this project.
        // This is to ensure Gradle is already downloaded and available in the environment.
        // Gradle Test Kit can download a Gradle distribution by itself,
        // but sometimes these downloads fail, making the test flaky.
        .withGradleVersion(System.getenv("GRADLE_VERSION"))
        .withArguments("hello", "--stacktrace")
        .forwardOutput()
        .build();

    assertTrue(result.getOutput().contains("Hello from my plugin!"));
  }
}
