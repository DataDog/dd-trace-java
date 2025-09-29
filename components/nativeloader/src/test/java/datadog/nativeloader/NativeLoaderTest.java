package datadog.nativeloader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;

public class NativeLoaderTest {
  @Test
  public void preloaded() throws LibraryLoadException {
    NativeLoader loader = NativeLoader.builder().preloaded("dne1", "dne2").build();

    try (LibFile lib = loader.resolveDynamic("dne1")) {
      assertTrue(lib.isPreloaded());

      assertNull(lib.file);
      assertFalse(lib.needsCleanup);

      // already considered loaded -- so this is a nop
      lib.load();
    }

    // already considered loaded -- so this is a nop
    loader.load("dne2");
  }

  @Test
  public void loadFailure() throws LibraryLoadException {
    NativeLoader loader = NativeLoader.builder().build();

    // test libraries are just text files, so they shouldn't load & link properly
    // NativeLoader is supposed to wrap the loading failures, so that we
    // remember to handle them
    assertThrows(LibraryLoadException.class, () -> loader.load("dummy"));
  }

  @Test
  public void fromFile() throws LibraryLoadException {
    NativeLoader loader = NativeLoader.builder().fromDir("test-data").build();

    try (LibFile lib = loader.resolveDynamic("dummy")) {
      // loaded directly from directory, so no clean-up required
      assertFalse(lib.needsCleanup);
    }
  }

  @Test
  public void fromDirBackedClassLoader() throws IOException, LibraryLoadException {
    // ClassLoader pulling from a directory, so there's still a normal file
    URL[] urls = {new File("test-data").toURL()};

    try (URLClassLoader classLoader = new URLClassLoader(urls)) {
      NativeLoader loader = NativeLoader.builder().fromClassLoader(classLoader).build();
      try (LibFile lib = loader.resolveDynamic("dummy")) {
        // since there's a normal file, no need to copy to a temp file and clean-up
        assertFalse(lib.needsCleanup);
      }
    }
  }

  @Test
  public void fromJarBackedClassLoader() throws IOException, LibraryLoadException {
    URL[] urls = {new File("test-data/libdummy.jar").toURL()};

    URLClassLoader classLoader = new URLClassLoader(urls);

    NativeLoader loader = NativeLoader.builder().fromClassLoader(classLoader).build();
    try (LibFile lib = loader.resolveDynamic("dummy")) {
      // loaded from a jar, so copied to temp file and requires clean-up
      assertTrue(lib.needsCleanup);
    }
  }
}
