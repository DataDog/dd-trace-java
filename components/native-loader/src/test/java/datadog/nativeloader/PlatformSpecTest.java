package datadog.nativeloader;

import static datadog.nativeloader.TestPlatformSpec.linux;
import static datadog.nativeloader.TestPlatformSpec.linux_arm32;
import static datadog.nativeloader.TestPlatformSpec.linux_arm64;
import static datadog.nativeloader.TestPlatformSpec.linux_x86_32;
import static datadog.nativeloader.TestPlatformSpec.linux_x86_64;
import static datadog.nativeloader.TestPlatformSpec.mac;
import static datadog.nativeloader.TestPlatformSpec.unsupportedArch;
import static datadog.nativeloader.TestPlatformSpec.unsupportedOs;
import static datadog.nativeloader.TestPlatformSpec.windows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class PlatformSpecTest {
  @Test
  public void macSpec() {
    assertTrue(mac().isMac());
    assertFalse(mac().isUnknownOs());
  }

  @Test
  public void linuxSpec() {
    assertTrue(linux().isLinux());
    assertFalse(linux().isUnknownOs());
  }

  @Test
  public void windowsSpec() {
    assertTrue(windows().isWindows());
    assertFalse(windows().isUnknownOs());
  }

  @Test
  public void unsupportedOsSpec() {
    assertFalse(unsupportedOs().isMac());
    assertFalse(unsupportedOs().isLinux());
    assertFalse(unsupportedOs().isWindows());
    assertTrue(unsupportedOs().isUnknownOs());
  }

  @Test
  public void arm32Spec() {
    assertTrue(linux_arm32().isArm32());
    assertFalse(linux_arm32().isUnknownArch());
  }

  @Test
  public void arm64Spec() {
    assertTrue(linux_arm64().isAarch64());
    assertFalse(linux_arm64().isUnknownArch());
  }

  @Test
  public void x86_32Spec() {
    assertTrue(linux_x86_32().isX86_32());
    assertFalse(linux_x86_32().isUnknownArch());
  }

  @Test
  public void x86_64Spec() {
    assertTrue(linux_x86_64().isX86_64());
    assertFalse(linux_x86_64().isUnknownArch());
  }

  @Test
  public void unsupportedArchSpec() {
    assertFalse(unsupportedArch().isArm32());
    assertFalse(unsupportedArch().isAarch64());
    assertFalse(unsupportedArch().isX86_32());
    assertFalse(unsupportedArch().isX86_64());
    assertTrue(unsupportedArch().isUnknownArch());
  }
}
