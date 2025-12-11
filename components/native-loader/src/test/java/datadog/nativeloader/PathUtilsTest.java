package datadog.nativeloader;

import static datadog.nativeloader.TestPlatformSpec.linux;
import static datadog.nativeloader.TestPlatformSpec.linux_arm32;
import static datadog.nativeloader.TestPlatformSpec.linux_arm64;
import static datadog.nativeloader.TestPlatformSpec.linux_glibc;
import static datadog.nativeloader.TestPlatformSpec.linux_musl;
import static datadog.nativeloader.TestPlatformSpec.linux_x86_32;
import static datadog.nativeloader.TestPlatformSpec.linux_x86_64;
import static datadog.nativeloader.TestPlatformSpec.mac;
import static datadog.nativeloader.TestPlatformSpec.unsupportedArch;
import static datadog.nativeloader.TestPlatformSpec.unsupportedOs;
import static datadog.nativeloader.TestPlatformSpec.windows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class PathUtilsTest {
  @Test
  public void libFileName_mac() {
    assertEquals("libtest.dylib", PathUtils.libFileName(mac(), "test"));
  }

  @Test
  public void libFileName_linux() {
    assertEquals("libtest.so", PathUtils.libFileName(linux(), "test"));
  }

  @Test
  public void libFileName_windows() {
    assertEquals("test.dll", PathUtils.libFileName(windows(), "test"));
  }

  @Test
  public void libPrefix_mac() {
    assertEquals("lib", PathUtils.libPrefix(mac()));
  }

  @Test
  public void libPrefix_linux() {
    assertEquals("lib", PathUtils.libPrefix(linux()));
  }

  @Test
  public void libPrefix_windows() {
    assertEquals("", PathUtils.libPrefix(windows()));
  }

  @Test
  public void libPrefix_unsupportedOs() {
    assertThrows(IllegalArgumentException.class, () -> PathUtils.libPrefix(unsupportedOs()));
  }

  @Test
  public void dynamicLibExtension_mac() {
    assertEquals("dylib", PathUtils.dynamicLibExtension(mac()));
  }

  @Test
  public void dynamicLibExtension_linux() {
    assertEquals("so", PathUtils.dynamicLibExtension(linux()));
  }

  @Test
  public void dynamicLibExtension_windows() {
    assertEquals("dll", PathUtils.dynamicLibExtension(windows()));
  }

  @Test
  public void dynamicLibExtension_unsupportedOs() {
    assertThrows(
        IllegalArgumentException.class, () -> PathUtils.dynamicLibExtension(unsupportedOs()));
  }

  @Test
  public void osPart_linux() {
    assertEquals("linux", PathUtils.osPartOf(linux()));
  }

  @Test
  public void osPart_mac() {
    assertEquals("macos", PathUtils.osPartOf(mac()));
  }

  @Test
  public void osPart_windows() {
    assertEquals("win", PathUtils.osPartOf(windows()));
  }

  @Test
  public void osPart_unsupportedOs() {
    assertThrows(IllegalArgumentException.class, () -> PathUtils.osPartOf(unsupportedOs()));
  }

  @Test
  public void archPart_x86_32() {
    assertEquals("x86_32", PathUtils.archPartOf(linux_x86_32()));
  }

  @Test
  public void archPart_x86_64() {
    assertEquals("x86_64", PathUtils.archPartOf(linux_x86_64()));
  }

  @Test
  public void archPart_arm32() {
    assertEquals("arm32", PathUtils.archPartOf(linux_arm32()));
  }

  @Test
  public void archPart_arm64() {
    assertEquals("aarch64", PathUtils.archPartOf(linux_arm64()));
  }

  @Test
  public void archPart_unsupportedArch() {
    assertThrows(IllegalArgumentException.class, () -> PathUtils.archPartOf(unsupportedArch()));
  }

  @Test
  public void libcPart_linux_glibc() {
    assertEquals("libc", PathUtils.libcPartOf(linux_glibc()));
  }

  @Test
  public void libcPart_linux_musl() {
    assertEquals("musl", PathUtils.libcPartOf(linux_musl()));
  }

  @Test
  public void libcPart_mac() {
    assertNull(PathUtils.libcPartOf(mac()));
  }

  @Test
  public void libcPart_windows() {
    assertNull(PathUtils.libcPartOf(windows()));
  }

  @Test
  public void concat_nonEmpty_nonEmpty() {
    assertEquals("foo/bar", PathUtils.concatPath("foo", "bar"));
  }

  @Test
  public void concat_null_nonEmpty() {
    assertEquals("bar", PathUtils.concatPath(null, "bar"));
  }

  @Test
  public void concat_nonEmpty_null() {
    assertEquals("foo", PathUtils.concatPath("foo", null));
  }

  @Test
  public void concat_empty_nonEmpty() {
    assertEquals("bar", PathUtils.concatPath("", "bar"));
  }

  @Test
  public void concat_null_empty_nonEmpty() {
    assertEquals("bar", PathUtils.concatPath(null, "", "bar"));
  }
}
