package datadog.trace.core.servicediscovery;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import org.junit.jupiter.api.Assumptions;
import org.mockito.MockedStatic;
import org.tabletest.junit.TableTest;

class ForeignMemoryWriterFactoryTest {

  @TableTest({
    "scenario           | osType  | architecture | javaAtLeast22 | expectedClassFragment",
    "macOS              | MACOS   | X64          | false         |                      ",
    "Windows            | WINDOWS | X64          | false         |                      ",
    "Linux unknown arch | LINUX   | UNKNOWN      | false         |                      ",
    "Linux pre-Java22   | LINUX   | X64          | false         | JNA                  ",
    "Linux Java22+      | LINUX   | X64          | true          | FFM                  "
  })
  void get(
      String scenario,
      String osType,
      String architecture,
      boolean javaAtLeast22,
      String expectedClassFragment) {
    // MemFDUnixWriterFFM uses java.lang.foreign and will fail to load on pre-22 JVMs
    boolean realJavaAtLeast22 = JavaVirtualMachine.isJavaVersionAtLeast(22);
    Assumptions.assumeTrue(!javaAtLeast22 || realJavaAtLeast22, "FFM writer requires Java 22+");
    try (MockedStatic<OperatingSystem> osMock = mockStatic(OperatingSystem.class);
        MockedStatic<JavaVirtualMachine> jvmMock = mockStatic(JavaVirtualMachine.class)) {
      osMock.when(OperatingSystem::type).thenReturn(OperatingSystem.Type.valueOf(osType));
      osMock
          .when(OperatingSystem::architecture)
          .thenReturn(OperatingSystem.Architecture.valueOf(architecture));
      jvmMock.when(() -> JavaVirtualMachine.isJavaVersionAtLeast(22)).thenReturn(javaAtLeast22);

      ForeignMemoryWriter writer = new ForeignMemoryWriterFactory().get();

      if (expectedClassFragment == null) {
        assertNull(writer, scenario);
      } else {
        assertNotNull(writer, scenario);
        assertTrue(writer.getClass().getName().contains(expectedClassFragment), scenario);
      }
    }
  }
}
