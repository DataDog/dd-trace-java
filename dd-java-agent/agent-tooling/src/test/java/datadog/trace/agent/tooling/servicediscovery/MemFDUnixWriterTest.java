package datadog.trace.agent.tooling.servicediscovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.environment.JavaVirtualMachine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@EnabledOnOs(OS.LINUX)
class MemFDUnixWriterTest {
  public static List<MemFDUnixWriter> writerProvider() {
    final List<MemFDUnixWriter> ret = new ArrayList<>();
    ret.add(new MemFDUnixWriterJNA()); // JNA is compatible with all the java versions
    if (JavaVirtualMachine.isJavaVersionAtLeast(22)) {
      ret.add(new MemFDUnixWriterFFM()); // FFM API is GA from java 22.
    }
    return ret;
  }

  @ParameterizedTest
  @MethodSource("writerProvider")
  void testWriteCreatesMemFd(MemFDUnixWriter writer) throws IOException {
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

  @ParameterizedTest
  @MethodSource("writerProvider")
  void testErrnoWorks(MemFDUnixWriter writer) {
    assertEquals(-1, writer.fcntl(-1, 0, 0)); // this call will fail
    assertTrue(writer.getLastError() > 0);
  }
}
