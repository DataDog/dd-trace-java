package datadog.nativeloader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Paths;
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
  public void defaultListeners() {
    NativeLoader.Builder builder = NativeLoader.builder();

    assertTrue(builder.listeners().isNop());
  }

  @Test
  public void addListener() {
    TestLibraryLoadingListener listener1 = new TestLibraryLoadingListener().expectLoad("foo");
    TestLibraryLoadingListener listener2 = listener1.copy();

    NativeLoader.Builder builder =
        NativeLoader.builder().addListener(listener1).addListener(listener2);

    SafeLibraryLoadingListener listener = builder.listeners();
    assertFalse(listener.isNop());

    listener.onLoad(builder.platformSpec(), null, "foo", false, Paths.get("/tmp/foo.dylib"));

    listener1.assertDone();
    listener2.assertDone();
  }

  @Test
  public void addListeners() {
    TestLibraryLoadingListener listener1 = new TestLibraryLoadingListener().expectLoad("foo");
    TestLibraryLoadingListener listener2 = listener1.copy();

    NativeLoader.Builder builder = NativeLoader.builder().addListeners(listener1, listener2);

    SafeLibraryLoadingListener listener = builder.listeners();
    assertFalse(listener.isNop());

    listener.onLoad(builder.platformSpec(), null, "foo", false, Paths.get("/tmp/foo.dylib"));

    listener1.assertDone();
    listener2.assertDone();
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
  public void preloaded() {
    PlatformSpec platformSpec =
        TestPlatformSpec.of(TestPlatformSpec.LINUX, TestPlatformSpec.ARM32, TestPlatformSpec.GLIBC);

    NativeLoader.Builder builder =
        NativeLoader.builder().platformSpec(platformSpec).preloaded("foo", "bar");

    LibraryResolver libResolver = builder.libResolver();
    assertTrue(libResolver.isPreloaded(platformSpec, "foo"));
    assertTrue(libResolver.isPreloaded(platformSpec, "bar"));

    assertFalse(libResolver.isPreloaded(platformSpec, "not-preloaded"));
  }

  @Test
  public void defaultPathLocator() {
    NativeLoader.Builder builder = NativeLoader.builder();

    assertEquals(PathLocators.defaultPathLocator(), builder.pathLocator());
  }

  @Test
  public void dirBasedLocator_string() {
    NativeLoader.Builder builder = NativeLoader.builder().fromDir("libs");

    assertEquals(PathLocators.fromLibDirs("libs"), builder.pathLocator());
  }

  @Test
  public void dirBasedLocator_strings_multiple() {
    NativeLoader.Builder builder = NativeLoader.builder().fromDirs("libs1", "libs2");

    assertEquals(PathLocators.fromLibDirs("libs1", "libs2"), builder.pathLocator());
  }

  @Test
  public void dirBasedLocator_file() {
    NativeLoader.Builder builder = NativeLoader.builder().fromDir(new File("libs"));

    assertEquals(PathLocators.fromLibDirs("libs"), builder.pathLocator());
  }

  @Test
  public void dirBasedLocator_files_multiple() {
    NativeLoader.Builder builder =
        NativeLoader.builder().fromDirs(new File("libs1"), new File("libs2"));

    assertEquals(PathLocators.fromLibDirs("libs1", "libs2"), builder.pathLocator());
  }

  @Test
  public void dirBasedLocator_path() {
    NativeLoader.Builder builder = NativeLoader.builder().fromDir(Paths.get("libs"));

    assertEquals(PathLocators.fromLibDirs("libs"), builder.pathLocator());
  }

  @Test
  public void dirBasedLocator_paths_multiple() {
    NativeLoader.Builder builder =
        NativeLoader.builder().fromDirs(Paths.get("libs1"), Paths.get("libs2"));

    assertEquals(PathLocators.fromLibDirs("libs1", "libs2"), builder.pathLocator());
  }

  @Test
  public void classLoaderBasedLocator() {
    ClassLoader sysClassLoader = ClassLoader.getSystemClassLoader();
    NativeLoader.Builder builder = NativeLoader.builder().fromClassLoader(sysClassLoader);

    assertEquals(PathLocators.fromClassLoader(sysClassLoader), builder.pathLocator());
  }

  @Test
  public void tempDir_string() {
    NativeLoader.Builder builder = NativeLoader.builder().tempDir("tmp");
    assertEquals(builder.tempDir(), Paths.get("tmp"));
  }

  @Test
  public void tempDir_file() {
    NativeLoader.Builder builder = NativeLoader.builder().tempDir(new File("tmp"));
    assertEquals(builder.tempDir(), Paths.get("tmp"));
  }

  @Test
  public void tempDir_path() {
    NativeLoader.Builder builder = NativeLoader.builder().tempDir(Paths.get("tmp"));
    assertEquals(builder.tempDir(), Paths.get("tmp"));
  }
}
