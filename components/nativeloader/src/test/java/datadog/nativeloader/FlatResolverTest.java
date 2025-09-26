package datadog.nativeloader;

import static datadog.nativeloader.TestPlatformSpec.AARCH64;
import static datadog.nativeloader.TestPlatformSpec.GLIBC;
import static datadog.nativeloader.TestPlatformSpec.LINUX;
import static datadog.nativeloader.TestPlatformSpec.MAC;
import static datadog.nativeloader.TestPlatformSpec.MUSL;
import static datadog.nativeloader.TestPlatformSpec.WINDOWS;
import static datadog.nativeloader.TestPlatformSpec.X86_64;

import org.junit.jupiter.api.Test;

public class FlatResolverTest {
  @Test
  public void linux_x86_64_libc() {
    test(TestPlatformSpec.of(LINUX, X86_64, GLIBC), "linux-x86_64-libc/libtest.so");
  }

  @Test
  public void linux_x86_64_musl() {
    test(TestPlatformSpec.of(LINUX, X86_64, MUSL), "linux-x86_64-musl/libtest.so");
  }

  @Test
  public void linux_x86_64_no_libc_flavor_fallback() {
    testFallback(1, TestPlatformSpec.of(LINUX, X86_64, GLIBC), "linux-x86_64/libtest.so");
  }

  @Test
  public void full_fallback() {
    testFallback(2, TestPlatformSpec.of(LINUX, X86_64, GLIBC), "libtest.so");
  }

  @Test
  public void osx_x86_64() {
    test(TestPlatformSpec.of(MAC, X86_64), "macos-x86_64/libtest.dylib");
  }

  @Test
  public void osx_aarch() {
    test(TestPlatformSpec.of(MAC, AARCH64), "macos-aarch64/libtest.dylib");
  }

  @Test
  public void windows_x86_64() {
    test(TestPlatformSpec.of(WINDOWS, X86_64), "win-x86_64/test.dll");
  }

  static final void test(PlatformSpec platformSpec, String expectedPath) {
    CapturingPathResolver locator = new CapturingPathResolver();
    FlatDirLibraryResolver.INSTANCE.resolve(locator, null, platformSpec, "test");

    locator.assertRequested(null, expectedPath);
  }

  static final void testFallback(
      int fallbackLevel, PlatformSpec platformSpec, String expectedPath) {
    CapturingPathResolver locator = new CapturingPathResolver(fallbackLevel);
    FlatDirLibraryResolver.INSTANCE.resolve(locator, null, platformSpec, "test");

    locator.assertRequested(null, expectedPath);
  }
}
