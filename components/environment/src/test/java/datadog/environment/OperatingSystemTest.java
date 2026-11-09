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
import java.nio.charset.Charset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class OperatingSystemTest {
  private String previousNativeEncoding;

  @BeforeEach
  void saveNativeEncoding() {
    previousNativeEncoding = System.getProperty("native.encoding");
  }

  @AfterEach
  void restoreNativeEncoding() {
    if (previousNativeEncoding == null) {
      System.clearProperty("native.encoding");
    } else {
      System.setProperty("native.encoding", previousNativeEncoding);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTF-8", "ISO-8859-1"})
  void usesNativeEncoding(String encoding) {
    System.setProperty("native.encoding", encoding);

    assertEquals(Charset.forName(encoding), OperatingSystem.nativeCharset());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "unsupported-charset", "invalid charset"})
  void fallsBackToDefaultCharset(String encoding) {
    if (encoding == null) {
      System.clearProperty("native.encoding");
    } else {
      System.setProperty("native.encoding", encoding);
    }

    assertEquals(Charset.defaultCharset(), OperatingSystem.nativeCharset());
  }

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
