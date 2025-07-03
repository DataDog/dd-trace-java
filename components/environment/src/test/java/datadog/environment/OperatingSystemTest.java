package datadog.environment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.OS.LINUX;
import static org.junit.jupiter.api.condition.OS.MAC;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;

class OperatingSystemTest {
  @Test
  @EnabledOnOs(LINUX)
  void onLinuxOnly() {
    assertTrue(OperatingSystem.isLinux());
    assertFalse(OperatingSystem.isMacOs());
    assertFalse(OperatingSystem.isWindows());
  }

  @Test
  @EnabledOnOs(MAC)
  void onMacOsOnly() {
    assertFalse(OperatingSystem.isLinux());
    assertTrue(OperatingSystem.isMacOs());
    assertFalse(OperatingSystem.isWindows());
  }

  @Test
  @EnabledOnOs(WINDOWS)
  void onWindowsOnly() {
    assertFalse(OperatingSystem.isLinux());
    assertFalse(OperatingSystem.isMacOs());
    assertTrue(OperatingSystem.isWindows());
  }
}
