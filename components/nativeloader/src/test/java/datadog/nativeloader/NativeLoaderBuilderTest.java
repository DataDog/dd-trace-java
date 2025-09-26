package datadog.nativeloader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

public class NativeLoaderBuilderTest {
  @Test
  public void defaultPlatformSpec() {
    NativeLoader.Builder builder = NativeLoader.builder();

    assertEquals(PlatformSpec.defaultPlatformSpec(), builder.platformSpec());
  }

  @Test
  public void customPlatformSpec() {
    PlatformSpec platformSpec =
        TestPlatformSpec.of(TestPlatformSpec.LINUX, TestPlatformSpec.ARM32, TestPlatformSpec.GLIBC);

    NativeLoader.Builder builder = NativeLoader.builder().platformSpec(platformSpec);
    assertSame(platformSpec, builder.platformSpec());
  }

  @Test
  public void defaultLibraryResolver() {
    NativeLoader.Builder builder = NativeLoader.builder();

    assertEquals(LibraryResolvers.defaultLibraryResolver(), builder.libResolver());
  }

  @Test
  public void flatLayout() {
    NativeLoader.Builder builder = NativeLoader.builder().flatLayout();

    assertEquals(LibraryResolvers.flatDirs(), builder.libResolver());
  }

  @Test
  public void nestedLayout() {
    NativeLoader.Builder builder = NativeLoader.builder().nestedLayout();

    assertEquals(LibraryResolvers.nestedDirs(), builder.libResolver());
  }

  @Test
  public void defaultPathLocator() {
    NativeLoader.Builder builder = NativeLoader.builder();

    assertEquals(PathLocators.defaultPathLocator(), builder.pathLocator());
  }

  @Test
  public void dirBasedLocator() {
    NativeLoader.Builder builder = NativeLoader.builder().fromDir("libs");

    assertEquals(PathLocators.fromLibDirs("libs"), builder.pathLocator());
  }

  @Test
  public void classLoaderBasedLocator() {
    ClassLoader sysClassLoader = ClassLoader.getSystemClassLoader();
    NativeLoader.Builder builder = NativeLoader.builder().fromClassLoader(sysClassLoader);

    assertEquals(PathLocators.fromClassLoader(sysClassLoader), builder.pathLocator());
  }
}
