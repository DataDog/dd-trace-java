package datadog.nativeloader;

import org.junit.jupiter.api.Test;

import static datadog.nativeloader.TestPlatformSpec.AARCH64;
import static datadog.nativeloader.TestPlatformSpec.GLIBC;
import static datadog.nativeloader.TestPlatformSpec.LINUX;
import static datadog.nativeloader.TestPlatformSpec.MAC;
import static datadog.nativeloader.TestPlatformSpec.MUSL;
import static datadog.nativeloader.TestPlatformSpec.WINDOWS;
import static datadog.nativeloader.TestPlatformSpec.X86_64;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PathUtilsTest {
  @Test
  public void libFileName_mac() {
	PlatformSpec macSpec = TestPlatformSpec.of(MAC, AARCH64);
	assertEquals("libtest.dylib", PathUtils.libFileName(macSpec, "test"));
  }
  
  @Test
  public void libFileName_linux() {
	PlatformSpec linuxSpec = TestPlatformSpec.of(LINUX, X86_64);
	assertEquals("libtest.so", PathUtils.libFileName(linuxSpec, "test"));
  }
  
  @Test
  public void libFileName_windows() {
	PlatformSpec linuxSpec = TestPlatformSpec.of(WINDOWS, X86_64);
	assertEquals("test.dll", PathUtils.libFileName(linuxSpec, "test"));	  
  }
}
