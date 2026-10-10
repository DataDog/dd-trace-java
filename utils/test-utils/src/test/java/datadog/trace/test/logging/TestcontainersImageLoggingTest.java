package datadog.trace.test.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestcontainersImageLoggingTest {
  @TempDir Path directory;

  @Test
  void preservesPullProgressAndCacheDecisionsAcrossLoggingResets() throws Exception {
    String previous = System.getProperty("testcontainers.image.log.dir");
    LoggerContext context = new LoggerContext();
    System.setProperty("testcontainers.image.log.dir", directory.toString());
    try {
      TestcontainersImageLogging.configure(context);
      TestcontainersImageLogging.configure(context);
      Logger image = context.getLogger("tc.registry.example/image@sha256:digest");
      image.setLevel(Level.INFO);
      image.info("Container output: CONTAINER_SENTINEL");
      try (Stream<Path> logs = Files.list(directory)) {
        assertEquals(0, logs.count(), "Setup and rejected events must not create log files");
      }
      context.reset();
      TestcontainersImageLogging.configure(context);
      image.setLevel(Level.INFO);
      try (Stream<Path> logs = Files.list(directory)) {
        assertEquals(0, logs.count(), "Resetting an unused appender must not create log files");
      }
      image.info("Pulling docker image: {}", "registry.example/image@sha256:digest");
      image.info("Starting to pull image");
      image.info(
          "Pulling image layers: {} pending, {} downloaded, {} extracted, ({}/{})",
          1,
          2,
          0,
          "10 MB",
          "? MB");
      image.warn("Retrying pull for image: {} ({}s remaining)", "image", 60);
      image.error("Docker image pull has not made progress in {}s - aborting pull", 60);
      image.error("Failed to pull image: {}", "image", new RuntimeException("EXCEPTION_SENTINEL"));
      image.info("Container output: CONTAINER_SENTINEL");
      context.getLogger("org.testcontainers.shaded.com.github.dockerjava").warn("COMMAND_SENTINEL");

      context.reset();
      TestcontainersImageLogging.configure(context);
      image.setLevel(Level.INFO);
      image.info(
          "Pull complete. {} layers, pulled in {}s (downloaded {} at {}/s)", 3, 8, "10 MB", "1 MB");
      image.info("Image {} pull took {}", "image", "PT8S");
      Logger policy = context.getLogger("org.testcontainers.images.AbstractImagePullPolicy");
      policy.setLevel(Level.DEBUG);
      policy.debug("Using locally available and not pulling image: {}", "image");
      context.stop();

      Path log;
      try (Stream<Path> logs = Files.list(directory)) {
        Path[] paths = logs.toArray(Path[]::new);
        assertEquals(1, paths.length);
        log = paths[0];
      }
      String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
      assertEquals(1, text.split("Starting to pull image", -1).length - 1);
      assertTrue(text.contains("1 pending, 2 downloaded"));
      assertTrue(text.contains("Retrying pull for image"));
      assertTrue(text.contains("has not made progress"));
      assertTrue(text.contains("Failed to pull image"));
      assertTrue(text.contains("pull took PT8S"));
      assertTrue(text.contains("Using locally available"));
      assertTrue(text.matches("(?s).*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z.*"));
      assertFalse(text.contains("EXCEPTION_SENTINEL"));
      assertFalse(text.contains("CONTAINER_SENTINEL"));
      assertFalse(text.contains("COMMAND_SENTINEL"));
    } finally {
      context.stop();
      if (previous == null) {
        System.clearProperty("testcontainers.image.log.dir");
      } else {
        System.setProperty("testcontainers.image.log.dir", previous);
      }
    }
  }
}
