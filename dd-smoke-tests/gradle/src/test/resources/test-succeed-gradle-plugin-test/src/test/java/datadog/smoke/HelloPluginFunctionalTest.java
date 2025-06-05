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
        .withGradleVersion("8.5")
        .withArguments("hello", "--stacktrace")
        .forwardOutput()
        .build();

    assertTrue(result.getOutput().contains("Hello from my plugin!"));
  }
}
