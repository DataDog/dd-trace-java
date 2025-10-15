package datadog.environment;

import static datadog.environment.OperatingSystem.Architecture.ARM;
import static datadog.environment.OperatingSystem.Architecture.ARM64;
import static datadog.environment.OperatingSystem.Architecture.X64;
import static datadog.environment.OperatingSystem.Architecture.X86;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.OS.LINUX;
import static org.junit.jupiter.api.condition.OS.MAC;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import datadog.environment.OperatingSystem.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;

class OperatingSystemTest {
  @Test
  @EnabledOnOs(LINUX)
  void onLinuxOnly() {
    assertTrue(OperatingSystem.isLinux());
    assertFalse(OperatingSystem.isMacOs());
    assertFalse(OperatingSystem.isWindows());
    assertEquals(Type.LINUX, OperatingSystem.type());
  }

  @Test
  @EnabledOnOs(MAC)
  void onMacOsOnly() {
    assertFalse(OperatingSystem.isLinux());
    assertTrue(OperatingSystem.isMacOs());
    assertFalse(OperatingSystem.isWindows());
    assertEquals(Type.MACOS, OperatingSystem.type());
  }

  @Test
  @EnabledOnOs(WINDOWS)
  void onWindowsOnly() {
    assertFalse(OperatingSystem.isLinux());
    assertFalse(OperatingSystem.isMacOs());
    assertTrue(OperatingSystem.isWindows());
    assertEquals(Type.WINDOWS, OperatingSystem.type());
  }

  @Test
  @EnabledOnOs(architectures = {"x86_64", "amd64", "k8"})
  void onX64() {
    assertEquals(X64, OperatingSystem.architecture());
  }

  @Test
  @EnabledOnOs(architectures = {"x86", "i386", "i486", "i586", "i686"})
  void onX86() {
    assertEquals(X86, OperatingSystem.architecture());
  }

  @Test
  @EnabledOnOs(architectures = {"arm", "aarch32"})
  void onArm() {
    assertEquals(ARM, OperatingSystem.architecture());
  }

  @Test
  @EnabledOnOs(architectures = {"arm64", "aarch64"})
  void onArm64() {
    assertEquals(ARM64, OperatingSystem.architecture());
  }
}
