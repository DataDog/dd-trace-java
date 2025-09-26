package datadog.nativeloader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

public class NativeLoaderTest {
  static final PlatformSpec TEST_PLATFORM_SPEC =
      TestPlatformSpec.of(TestPlatformSpec.MAC, TestPlatformSpec.AARCH64);

  static final NativeLoader.Builder buildTestLoader() {
    return NativeLoader.builder().platformSpec(TEST_PLATFORM_SPEC);
  }

  @Test
  public void preloaded() throws LibraryLoadException {
    NativeLoader loader = buildTestLoader().preloaded("dne1", "dne2").build();

    try (LibFile lib = loader.resolveDynamic("dne1")) {
      assertTrue(lib.isPreloaded());

      assertNull(lib.file);
      assertFalse(lib.needsCleanup);

      // already consider loaded -- so this is a nop
      lib.load();
    }

    // already consider loaded -- so this is a nop
    loader.load("dne2");
  }

  @Test
  public void fromFile() throws LibraryLoadException {
    //    NativeLoader loader = buildTestLoader().fromDir("native-lib").build();
    //
    //    try ( LibFile lib = loader.resolveDynamic("test") ) {
    //      assertFalse(lib.needsCleanup);
    //    }
  }

  @Test
  public void fromDirBackedClassLoader() throws IOException, LibraryLoadException {
    //    // ClassLoader pulling from a directory, so there's still a normal file
    //    URL[] urls = {
    //      new File("native-lib").toURL()
    //    };
    //
    //    URLClassLoader classLoader = new URLClassLoader(urls);
    //
    //    NativeLoader loader = buildTestLoader().fromClassLoader(classLoader).build();
    //    try ( LibFile lib = loader.resolveDynamic("test") ) {
    //      // since there's a normal file, no need for clean-up
    //      assertFalse(lib.needsCleanup);
    //    }
  }

  @Test
  public void fromJarBackedClassLoader() throws IOException, LibraryLoadException {
    //    URL[] urls = {
    //      new File("native-lib/libdatadog_library_config.jar").toURL()
    //    };
    //
    //    URLClassLoader classLoader = new URLClassLoader(urls);
    //
    //    NativeLoader loader = buildTestLoader().fromClassLoader(classLoader).build();
    //    try ( LibFile lib = loader.resolveDynamic("test") ) {
    //      assertTrue(lib.needsCleanup);
    //    }
  }
}
