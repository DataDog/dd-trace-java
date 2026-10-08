package datadog.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.environment.OperatingSystem;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class HostInfoTest {

  @Test
  void getHostname() {
    String hostname = HostInfo.getHostname();

    assertNotNull(hostname);
    assertFalse(hostname.trim().isEmpty());
  }

  @Test
  void getOsName() {
    String osName = HostInfo.getOsName();

    assertTrue(Arrays.asList("Linux", "Windows", "Darwin").contains(osName));
  }

  @Test
  void getOsVersion() {
    String osVersion = HostInfo.getOsVersion();

    assertNotNull(osVersion);
    assertFalse(osVersion.trim().isEmpty());
  }

  @Test
  void compareToUname() throws IOException, InterruptedException {
    assumeTrue(exitCode("uname", "-a") == 0);

    assertEquals(runCommand("uname", "-n"), HostInfo.getHostname());
    assertEquals(runCommand("uname", "-s"), HostInfo.getOsName());
    assertEquals(runCommand("uname", "-s"), HostInfo.getKernelName());
    if (OperatingSystem.isMacOs()) {
      // uname -r will return X.Y.Z version, while JVM will report just X.Y
      // disabled, the uname -r gives the Kernel version which is different from Mac OS version
      // assertTrue(runCommand("uname", "-r").startsWith(HostInfo.getKernelRelease()));

      // No /proc in macOS, so using property os.version like for KernelRelease
      assertEquals(HostInfo.getKernelRelease(), HostInfo.getKernelVersion());
    } else {
      assertEquals(runCommand("uname", "-r"), HostInfo.getKernelRelease());
      // Ideally, this would be equal, but for now, we'll compromise to startWith.
      assertTrue(runCommand("uname", "-v").startsWith(HostInfo.getKernelVersion()));
    }
  }

  private static String runCommand(String... command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).start();
    String output;
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      output = reader.lines().collect(Collectors.joining("\n"));
    }
    process.waitFor();
    return output.trim();
  }

  private static int exitCode(String... command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).start();
    return process.waitFor();
  }
}
