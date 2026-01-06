package datadog.trace.agent.tooling.servicediscovery;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class ForeignMemoryWriterImplTest {

  private ForeignMemoryWriterImpl writer;

  @BeforeEach
  void setUp() {
    writer = new ForeignMemoryWriterImpl();
  }

  @Test
  void testWriteCreatesMemFd() throws IOException {
    // Given
    String fileName = "test-memfd-" + System.currentTimeMillis();
    String testContent = "Hello from Foreign Memory API!";
    byte[] payload = testContent.getBytes(StandardCharsets.UTF_8);

    // When
    writer.write(fileName, payload);

    // Then - verify memfd was created by checking /proc/self/fd
    // The memfd should be open and readable
    Path procSelfFd = Paths.get("/proc/self/fd");
    boolean memfdFound = false;

    try (Stream<Path> fdStream = Files.list(procSelfFd)) {
      memfdFound =
          fdStream.anyMatch(
              fd -> {
                try {
                  Path linkTarget = Files.readSymbolicLink(fd);
                  return linkTarget.toString().contains(fileName);
                } catch (IOException e) {
                  return false;
                }
              });
    }

    assertTrue(memfdFound, "memfd should be created and visible in /proc/self/fd");
  }
}
