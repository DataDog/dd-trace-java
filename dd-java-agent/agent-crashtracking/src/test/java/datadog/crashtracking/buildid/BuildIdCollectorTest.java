package datadog.crashtracking.buildid;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class BuildIdCollectorTest {

  @TempDir Path tempDir;

  @Test
  void testAwaitCollectionDoneWithinTimeout() throws IOException {
    BuildIdCollector collector = new BuildIdCollector();

    // Add a library to process
    String filename = "test-library.so";
    collector.addUnprocessedLibrary(filename);

    // Create a simple test file
    Path testFile = tempDir.resolve(filename);
    Files.write(testFile, new byte[] {0x7F, 'E', 'L', 'F'}); // ELF magic bytes

    // Start collection
    collector.resolveBuildId(testFile);

    // Should complete within timeout
    long startTime = System.currentTimeMillis();
    collector.awaitCollectionDone(5);
    long elapsedTime = System.currentTimeMillis() - startTime;

    // Verify it completed quickly (well under 5 seconds)
    assertTrue(elapsedTime < 5000, "Collection should complete quickly");
  }

  @Test
  void testAwaitCollectionDoneWithoutStartingCollection() {
    BuildIdCollector collector = new BuildIdCollector();

    // awaitCollectionDone without starting collection should return immediately
    long startTime = System.currentTimeMillis();
    collector.awaitCollectionDone(5);
    long elapsedTime = System.currentTimeMillis() - startTime;

    assertTrue(elapsedTime < 100, "Should return immediately when not collecting");
  }

  @Test
  void testResolveBuildIdSkipsUnprocessedLibraries() throws IOException {
    BuildIdCollector collector = new BuildIdCollector();

    String filename = "not-added.so";
    Path testFile = tempDir.resolve(filename);
    Files.write(testFile, new byte[] {0x7F, 'E', 'L', 'F'});

    // Resolve without adding first
    collector.resolveBuildId(testFile);
    collector.awaitCollectionDone(1);

    // Should not be in the map since it wasn't added
    BuildInfo info = collector.getBuildInfo(filename);
    assertNull(info, "Library should not be processed if not added first");
  }

  @Test
  void testMultipleAwaitCollectionDoneCalls() throws IOException {
    BuildIdCollector collector = new BuildIdCollector();

    String filename = "test.so";
    collector.addUnprocessedLibrary(filename);
    Path testFile = tempDir.resolve(filename);
    Files.write(testFile, new byte[] {0x7F, 'E', 'L', 'F'});

    collector.resolveBuildId(testFile);
    collector.awaitCollectionDone(5);

    // Second call should return immediately since collection is already done
    long startTime = System.currentTimeMillis();
    collector.awaitCollectionDone(5);
    long elapsedTime = System.currentTimeMillis() - startTime;

    assertTrue(elapsedTime < 100, "Second await should return immediately");
  }
}
