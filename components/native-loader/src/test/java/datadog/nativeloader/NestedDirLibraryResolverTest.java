package datadog.nativeloader;

import static datadog.nativeloader.TestPlatformSpec.AARCH64;
import static datadog.nativeloader.TestPlatformSpec.GLIBC;
import static datadog.nativeloader.TestPlatformSpec.LINUX;
import static datadog.nativeloader.TestPlatformSpec.MAC;
import static datadog.nativeloader.TestPlatformSpec.MUSL;
import static datadog.nativeloader.TestPlatformSpec.WINDOWS;
import static datadog.nativeloader.TestPlatformSpec.X86_64;

import org.junit.jupiter.api.Test;

public class NestedDirLibraryResolverTest {
  @Test
  public void linux_x86_64_libc() {
    test(
        TestPlatformSpec.of(LINUX, X86_64, GLIBC),
        "linux/x86_64/libc/libtest.so",
        "linux/x86_64/libtest.so",
        "linux/libtest.so",
        "libtest.so");
  }

  @Test
  public void linux_x86_64_musl() {
    test(
        TestPlatformSpec.of(LINUX, X86_64, MUSL),
        "linux/x86_64/musl/libtest.so",
        "linux/x86_64/libtest.so",
        "linux/libtest.so",
        "libtest.so");
  }

  @Test
  public void osx_x86_64() {
    test(
        TestPlatformSpec.of(MAC, X86_64),
        "macos/x86_64/libtest.dylib",
        "macos/libtest.dylib",
        "libtest.dylib");
  }

  @Test
  public void osx_aarch() {
    test(
        TestPlatformSpec.of(MAC, AARCH64),
        "macos/aarch64/libtest.dylib",
        "macos/libtest.dylib",
        "libtest.dylib");
  }

  @Test
  public void windows_x86_64() {
    test(TestPlatformSpec.of(WINDOWS, X86_64), "win/x86_64/test.dll", "win/test.dll", "test.dll");
  }

  static final void test(PlatformSpec platformSpec, String... expectedPaths) {
    CapturingPathLocator.testFailOnExceptions(
        NestedDirLibraryResolver.INSTANCE,
        platformSpec,
        CapturingPathLocator.WITH_OMIT_COMP_FALLBACK,
        expectedPaths);
  }
}
