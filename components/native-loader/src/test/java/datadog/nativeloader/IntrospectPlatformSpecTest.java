package datadog.nativeloader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.environment.OperatingSystem;
import datadog.environment.OperatingSystem.Architecture;
import org.junit.jupiter.api.Test;

public class IntrospectPlatformSpecTest {
  @Test
  public void os() {
    PlatformSpec platformSpec = IntrospectPlatformSpec.INSTANCE;

    // a bit silly since this just mirrors the underlying implementation
    assertEquals(OperatingSystem.isMacOs(), platformSpec.isMac());
    assertEquals(OperatingSystem.isLinux(), platformSpec.isLinux());
    assertEquals(OperatingSystem.isWindows(), platformSpec.isWindows());
  }

  @Test
  public void arch() {
    PlatformSpec platformSpec = IntrospectPlatformSpec.INSTANCE;

    assertEquals(OperatingSystem.architecture() == Architecture.X86, platformSpec.isX86_32());
    assertEquals(OperatingSystem.architecture() == Architecture.X64, platformSpec.isX86_64());
    assertEquals(OperatingSystem.architecture() == Architecture.ARM, platformSpec.isArm32());
    assertEquals(OperatingSystem.architecture() == Architecture.ARM64, platformSpec.isAarch64());
  }

  @Test
  public void musl() {
    PlatformSpec platformSpec = IntrospectPlatformSpec.INSTANCE;

    assertEquals(OperatingSystem.isMusl(), platformSpec.isMusl());
  }

  @Test
  public void equals() {
    // just a sanity check, since assertEquals is used in other tests
    assertNotEquals(TestPlatformSpec.unsupportedOs(), IntrospectPlatformSpec.INSTANCE);

    assertEquals(IntrospectPlatformSpec.INSTANCE, IntrospectPlatformSpec.INSTANCE);
  }
}
