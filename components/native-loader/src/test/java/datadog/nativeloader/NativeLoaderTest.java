package datadog.nativeloader;

import static datadog.nativeloader.TestPlatformSpec.AARCH64;
import static datadog.nativeloader.TestPlatformSpec.LINUX;
import static datadog.nativeloader.TestPlatformSpec.UNSUPPORTED_ARCH;
import static datadog.nativeloader.TestPlatformSpec.UNSUPPORTED_OS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;

public class NativeLoaderTest {
  @Test
  public void preloaded() throws LibraryLoadException {
    NativeLoader loader = NativeLoader.builder().preloaded("preloaded1", "preloaded2").build();

    assertTrue(loader.isPreloaded("preloaded1"));
    assertTrue(loader.isPreloaded("preloaded2"));

    assertFalse(loader.isPreloaded("dne"));

    try (LibFile lib = loader.resolveDynamic("preloaded1")) {
      assertPreloaded(lib);

      // already considered loaded -- so this is a nop
      lib.load();
    }

    // already considered loaded -- so this is a nop
    loader.load("preloaded2");

    // not already loaded - so passes through to underlying resolver
    assertThrows(LibraryLoadException.class, () -> loader.load("dne"));
  }

  @Test
  public void preloaded_listenerSupport() throws LibraryLoadException {
    TestLibraryLoadingListener sharedListener = new TestLibraryLoadingListener();

    NativeLoader loader =
        NativeLoader.builder()
            .preloaded("preloaded1", "preloaded2")
            .addListener(sharedListener)
            .build();

    // debatable - but no listener calls just for checking
    assertTrue(loader.isPreloaded("preloaded1"));
    assertTrue(loader.isPreloaded("preloaded2"));

    sharedListener.expectResolvePreloaded("preloaded1");
    sharedListener.expectLoadPreloaded("preloaded1");

    TestLibraryLoadingListener scopedListener1 =
        new TestLibraryLoadingListener()
            .expectResolvePreloaded("preloaded1")
            .expectLoadPreloaded("preloaded1");

    try (LibFile lib = loader.resolveDynamic("preloaded1", scopedListener1)) {
      lib.load();
    }

    sharedListener.assertDone();
    scopedListener1.assertDone();

    sharedListener.expectResolvePreloaded("preloaded2");
    sharedListener.expectLoadPreloaded("preloaded2");

    TestLibraryLoadingListener scopedListener2 =
        new TestLibraryLoadingListener()
            .expectResolvePreloaded("preloaded2")
            .expectLoadPreloaded("preloaded2");

    // load is just convenience for resolve & load
    loader.load("preloaded2", scopedListener2);

    sharedListener.assertDone();
    scopedListener2.assertDone();

    sharedListener.expectResolveDynamicFailure("dne");

    TestLibraryLoadingListener scopedListener3 =
        new TestLibraryLoadingListener().expectResolveDynamicFailure("dne");

    // not already loaded - so passes through to underlying resolver
    assertThrows(LibraryLoadException.class, () -> loader.load("dne", scopedListener3));

    sharedListener.assertDone();
    scopedListener3.assertDone();
  }

  @Test
  public void unsupportedPlatform() {
    TestLibraryLoadingListener sharedListener = new TestLibraryLoadingListener();

    PlatformSpec unsupportedOsSpec = TestPlatformSpec.of(UNSUPPORTED_OS, AARCH64);
    NativeLoader loader =
        NativeLoader.builder().platformSpec(unsupportedOsSpec).addListener(sharedListener).build();
    
    assertFalse(loader.isPlatformSupported());

    sharedListener.expectResolveDynamicFailure("dummy");

    // short-circuit fails during resolution because os isn't supported
    assertThrows(LibraryLoadException.class, () -> loader.resolveDynamic("dummy"));

    sharedListener.assertDone();
  }

  @Test
  public void unsupportArch() {
    PlatformSpec unsupportedOsSpec = TestPlatformSpec.of(LINUX, UNSUPPORTED_ARCH);
    NativeLoader loader = NativeLoader.builder().platformSpec(unsupportedOsSpec).build();

    assertFalse(loader.isPlatformSupported());
    
    TestLibraryLoadingListener scopedListener =
        new TestLibraryLoadingListener().expectResolveDynamicFailure("dummy");

    // short-circuit fails during resolution because arch isn't supported
    assertThrows(LibraryLoadException.class, () -> loader.resolveDynamic("dummy", scopedListener));

    scopedListener.assertDone();
  }

  @Test
  public void loadFailure() throws LibraryLoadException {
    TestLibraryLoadingListener sharedListener = new TestLibraryLoadingListener();

    NativeLoader loader = NativeLoader.builder().addListener(sharedListener).build();
    assumeTrue(loader.isPlatformSupported());

    sharedListener.expectResolveDynamic("dummy");
    sharedListener.expectLoadFailure("dummy");

    TestLibraryLoadingListener scopedListener =
        new TestLibraryLoadingListener().expectResolveDynamic("dummy").expectLoadFailure("dummy");

    // test libraries are just text files, so they shouldn't load & link properly
    // NativeLoader is supposed to wrap the loading failures, so that we
    // remember to handle them

    // on supported platforms, there is a dummy library file, so this will resolve but fail to load
    // & link
    assertThrows(LibraryLoadException.class, () -> loader.load("dummy", scopedListener));
  }
  
  @Test
  public void resolutionFailure_in_LibraryResolver() {
	Exception exception = new Exception("boom!");
	
	NativeLoader loader = NativeLoader.builder().libResolver((pathLocator, platformSpec, component, libName) -> {
	  throw exception;
	}).build();
	
	TestLibraryLoadingListener scopedListener = new TestLibraryLoadingListener().
	  expectResolveDynamicFailure("dummy", exception);
	
	assertThrows(LibraryLoadException.class, () -> loader.load("dummy", scopedListener));
	
	scopedListener.assertDone();
  }
  
  @Test
  public void resolutionFailure_in_PathLocator() {
	TestLibraryLoadingListener sharedListener = new TestLibraryLoadingListener();
	
	Exception exception = new Exception("boom!");
	
	NativeLoader loader = NativeLoader.builder().addListener(sharedListener).pathLocator((comp, path) -> {
	  throw exception;
	}).build();
	
	sharedListener.expectResolveDynamicFailure("dummy", exception);
	
	assertThrows(LibraryLoadException.class, () -> loader.load("dummy"));
	
	sharedListener.assertDone();  
  }

  @Test
  public void fromDir() throws LibraryLoadException {
    NativeLoader loader = NativeLoader.builder().fromDir("test-data").build();

    try (LibFile lib = loader.resolveDynamic("dummy")) {
      // loaded directly from directory, so no clean-up required
      assertRegularFile(lib);

      // file isn't actually a dynamic library
      assertThrows(LibraryLoadException.class, () -> lib.load());
    }
  }

  @Test
  public void fromDir_override_windows() throws LibraryLoadException {
    TestLibraryLoadingListener sharedListener = new TestLibraryLoadingListener();

    NativeLoader loader =
        NativeLoader.builder().fromDir("test-data").addListener(sharedListener).build();

    sharedListener.expectResolveDynamic(TestPlatformSpec.windows(), "dummy");

    try (LibFile lib = loader.resolveDynamic(TestPlatformSpec.windows(), "dummy")) {
      // loaded directly from directory, so no clean-up required
      assertRegularFile(lib);
      assertTrue(lib.getAbsolutePath().endsWith("dummy.dll"));
    }

    sharedListener.assertDone();
  }

  @Test
  public void fromDir_override_mac() throws LibraryLoadException {
    NativeLoader loader = NativeLoader.builder().fromDir("test-data").build();

    TestLibraryLoadingListener scopedListener =
        new TestLibraryLoadingListener().expectResolveDynamic(TestPlatformSpec.mac(), "dummy");

    try (LibFile lib = loader.resolveDynamic(TestPlatformSpec.mac(), "dummy", scopedListener)) {
      // loaded directly from directory, so no clean-up required
      assertRegularFile(lib);
      assertTrue(lib.getAbsolutePath().endsWith("libdummy.dylib"));
    }

    scopedListener.assertDone();
  }

  @Test
  public void fromDir_override_linux() throws LibraryLoadException {
    NativeLoader loader = NativeLoader.builder().fromDir("test-data").build();

    try (LibFile lib = loader.resolveDynamic(TestPlatformSpec.linux(), "dummy")) {
      // loaded directly from directory, so no clean-up required
      assertRegularFile(lib);
      assertTrue(lib.getAbsolutePath().endsWith("libdummy.so"));
    }
  }

  @Test
  public void fromDirList() throws LibraryLoadException {
    NativeLoader loader = NativeLoader.builder().fromDirs("dne1", "dne2", "test-data").build();

    try (LibFile lib = loader.resolveDynamic("dummy")) {
      // loaded directly from directory, so no clean-up required
      assertRegularFile(lib);
    }
  }

  @Test
  public void fromDir_with_component() throws LibraryLoadException {
    TestLibraryLoadingListener sharedListener = new TestLibraryLoadingListener();

    NativeLoader loader =
        NativeLoader.builder().fromDir("test-data").addListener(sharedListener).build();

    sharedListener.expectResolveDynamic("comp1", "dummy");

    try (LibFile lib = loader.resolveDynamic("comp1", "dummy")) {
      assertRegularFile(lib);
      assertTrue(lib.getAbsolutePath().contains("comp1"));
    }

    sharedListener.assertDone();

    sharedListener.expectResolveDynamic("comp2", "dummy");

    try (LibFile lib = loader.resolveDynamic("comp2", "dummy")) {
      assertRegularFile(lib);
      assertTrue(lib.getAbsolutePath().contains("comp2"));
    }

    sharedListener.assertDone();
  }

  @Test
  public void fromDir_load_with_component() {
    NativeLoader loader = NativeLoader.builder().fromDir("test-data").build();

    // lib file is a dummy, so fails during loading and linking
    assertThrows(LibraryLoadException.class, () -> loader.load("comp1", "dummy"));

    TestLibraryLoadingListener scopedListener2 =
        new TestLibraryLoadingListener()
            .expectResolveDynamic("comp2", "dummy")
            .expectLoadFailure("comp2", "dummy");

    assertThrows(LibraryLoadException.class, () -> loader.load("comp2", "dummy", scopedListener2));

    scopedListener2.assertDone();
  }

  @Test
  public void fromDirBackedClassLoader() throws IOException, LibraryLoadException {
    // ClassLoader pulling from a directory, so there's still a normal file
    URL[] urls = {new File("test-data").toURL()};

    try (URLClassLoader classLoader = new URLClassLoader(urls)) {
      NativeLoader loader = NativeLoader.builder().fromClassLoader(classLoader).build();
      try (LibFile lib = loader.resolveDynamic("dummy")) {
        // since there's a normal file, no need to copy to a temp file and clean-up
        assertRegularFile(lib);
      }
    }
  }

  @Test
  public void fromDirBackedClassLoader_with_component() throws IOException, LibraryLoadException {
    // ClassLoader pulling from a directory, so there's still a normal file
    URL[] urls = {new File("test-data").toURL()};

    try (URLClassLoader classLoader = new URLClassLoader(urls)) {
      NativeLoader loader = NativeLoader.builder().fromClassLoader(classLoader).build();

      try (LibFile lib = loader.resolveDynamic("comp1", "dummy")) {
        assertRegularFile(lib);
        assertTrue(lib.getAbsolutePath().contains("comp1"));
      }

      try (LibFile lib = loader.resolveDynamic("comp2", "dummy")) {
        assertRegularFile(lib);
        assertTrue(lib.getAbsolutePath().contains("comp2"));
      }
    }
  }

  @Test
  public void fromDirBackedClassLoader_with_subResource() throws IOException, LibraryLoadException {
    // ClassLoader pulling from a directory, so there's still a normal file
    URL[] urls = {new File("test-data").toURL()};

    try (URLClassLoader classLoader = new URLClassLoader(urls)) {
      NativeLoader loader = NativeLoader.builder().fromClassLoader(classLoader, "resource").build();
      try (LibFile lib = loader.resolveDynamic("dummy")) {
        // since there's a normal file, no need to copy to a temp file and clean-up
        assertRegularFile(lib);
        assertTrue(lib.getAbsolutePath().contains("resource"));
      }
    }
  }

  @Test
  public void fromDirBackedClassLoader_with_subResource_and_comp()
      throws IOException, LibraryLoadException {
    // ClassLoader pulling from a directory, so there's still a normal file
    try (URLClassLoader classLoader = createClassLoader(Paths.get("test-data"))) {
      NativeLoader loader = NativeLoader.builder().fromClassLoader(classLoader, "resource").build();
      try (LibFile lib = loader.resolveDynamic("comp1", "dummy")) {
        // since there's a normal file, no need to copy to a temp file and clean-up
        assertRegularFile(lib);
        assertTrue(lib.getAbsolutePath().contains("comp1"));
        assertTrue(lib.getAbsolutePath().contains("resource"));
      }
    }
  }

  @Test
  public void fromJarBackedClassLoader() throws IOException, LibraryLoadException {
    Path jar = jar("test-data");
    try {
      try (URLClassLoader classLoader = createClassLoader(jar)) {
        NativeLoader loader = NativeLoader.builder().fromClassLoader(classLoader).build();

        TestLibraryLoadingListener scopedListener =
            new TestLibraryLoadingListener()
                .expectResolveDynamic("dummy")
                .expectTempFileCreated("dummy")
                .expectTempFileCleanup("dummy");

        try (LibFile lib = loader.resolveDynamic("dummy", scopedListener)) {
          // loaded from a jar, so copied to temp file
          assertTempFile(lib);
        }

        scopedListener.assertDone();
      }
    } finally {
      deleteHelper(jar);
    }
  }

  @Test
  public void fromJarBackedClassLoader_load_with_component()
      throws IOException, LibraryLoadException {
    Path jar = jar("test-data");
    try {
      try (URLClassLoader classLoader = createClassLoader(jar)) {
        NativeLoader loader = NativeLoader.builder().fromClassLoader(classLoader).build();

        // lib file is a dummy, so fails during loading and linking
        assertThrows(LibraryLoadException.class, () -> loader.load("comp1", "dummy"));

        TestLibraryLoadingListener scopedListener2 =
            new TestLibraryLoadingListener()
                .expectResolveDynamic("comp2", "dummy")
                .expectTempFileCreated("comp2", "dummy")
                .expectLoadFailure("comp2", "dummy")
                .expectTempFileCleanup("comp2", "dummy");

        assertThrows(
            LibraryLoadException.class, () -> loader.load("comp2", "dummy", scopedListener2));

        scopedListener2.assertDone();
      }
    } finally {
      deleteHelper(jar);
    }
  }

  @Test
  public void fromJarBackedClassLoader_with_tempDir() throws IOException, LibraryLoadException {
    Path jar = jar("test-data");
    try {
      Path tempDir = Paths.get("temp");
      deleteHelper(tempDir);

      try (URLClassLoader classLoader = createClassLoader(jar)) {
        NativeLoader loader =
            NativeLoader.builder().fromClassLoader(classLoader).tempDir(tempDir).build();
        try (LibFile lib = loader.resolveDynamic("dummy")) {
          // loaded from a jar, so copied to temp file
          assertTempFile(lib);
        }
      } finally {
        deleteHelper(tempDir);
      }
    } finally {
      deleteHelper(jar);
    }
  }

  @Test
  public void fromJarBackedClassLoader_with_unwritable_tempDir()
      throws IOException, LibraryLoadException {
    Path jar = jar("test-data");
    try {
      Path noWriteDir = Paths.get("no-write-temp");
      deleteHelper(noWriteDir);
      Files.createDirectories(noWriteDir, posixAttr("r-x------"));

      try (URLClassLoader classLoader = createClassLoader(jar)) {
        NativeLoader loader =
            NativeLoader.builder().fromClassLoader(classLoader).tempDir(noWriteDir).build();

        TestLibraryLoadingListener scopedListener =
            new TestLibraryLoadingListener()
                .expectResolveDynamic("dummy")
                .expectTempFileCreationFailure("dummy");

        // unable to resolve to a File because tempDir isn't writable
        assertThrows(
            LibraryLoadException.class, () -> loader.resolveDynamic("dummy", scopedListener));

        scopedListener.assertDone();
      } finally {
        deleteHelper(noWriteDir);
      }
    } finally {
      deleteHelper(jar);
    }
  }

  @Test
  public void fromJarBackedClassLoader_with_locked_file() throws IOException, LibraryLoadException {
    Path jar = jar("test-data");
    try {
      Path tempDir = Paths.get("temp");
      deleteHelper(tempDir);

      try (URLClassLoader classLoader = createClassLoader(jar)) {
        NativeLoader loader =
            NativeLoader.builder().fromClassLoader(classLoader).tempDir(tempDir).build();
        try (LibFile lib = loader.resolveDynamic("dummy")) {
          // loaded from a jar, so copied to temp file
          assertTempFile(lib);

          // simulating lock - by blocking ability to delete from parent dir
          // forces fallback to deleteOnExit
          Files.setPosixFilePermissions(lib.toPath().getParent(), posixPerms("r-x------"));
        }
      } finally {
        deleteHelper(tempDir);
      }
    } finally {
      deleteHelper(jar);
    }
  }

  void deleteHelper(Path dir) {
    try {
      Files.setPosixFilePermissions(dir, posixPerms("rwx------"));
      Files.delete(dir);
    } catch (IOException e) {
    }
  }

  static URLClassLoader createClassLoader(Path... paths) {
    return new URLClassLoader(urls(paths));
  }

  static URL[] urls(Path... paths) {
    URL[] urls = new URL[paths.length];
    for (int i = 0; i < urls.length; ++i) {
      try {
        urls[i] = paths[i].toUri().toURL();
      } catch (MalformedURLException e) {
        throw new IllegalStateException(e);
      }
    }
    return urls;
  }

  static Path jar(String dirName) {
    return jar(Paths.get(dirName));
  }

  static Path jar(Path dir) {
    try {
      return jarHelper(dir);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static Path jarHelper(Path dir) throws IOException {
    Path jarPath = Files.createTempFile(dir.toFile().getName(), ".jar", posixAttr("rwx------"));

    try (JarOutputStream jarStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
      Files.walk(dir)
          .filter(path -> !Files.isDirectory(path))
          .forEach(
              path -> {
                try {
                  JarEntry jarEntry = new JarEntry(dir.relativize(path).toString());
                  jarStream.putNextEntry(jarEntry);
                  Files.copy(path, jarStream);
                  jarStream.closeEntry();
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    }
    return jarPath;
  }

  static FileAttribute<Set<PosixFilePermission>> posixAttr(String posixStr) {
    return PosixFilePermissions.asFileAttribute(posixPerms(posixStr));
  }

  static Set<PosixFilePermission> posixPerms(String posixStr) {
    return PosixFilePermissions.fromString(posixStr);
  }

  static void assertPreloaded(LibFile lib) {
    assertTrue(lib.isPreloaded());
    assertNull(lib.toFile());
    assertNull(lib.toPath());
    assertNull(lib.getAbsolutePath());
    assertFalse(lib.needsCleanup);
  }

  static void assertRegularFile(LibFile lib) {
    assertFalse(lib.isPreloaded());
    assertNotNull(lib.toFile());
    assertNotNull(lib.toPath());
    assertTrue(lib.toFile().exists());
    assertNotNull(lib.getAbsolutePath());
    assertFalse(lib.needsCleanup);
  }

  static void assertTempFile(LibFile lib) {
    assertFalse(lib.isPreloaded());
    assertNotNull(lib.toFile());
    assertNotNull(lib.toPath());
    assertTrue(lib.toFile().exists());
    assertNotNull(lib.getAbsolutePath());
    assertTrue(lib.needsCleanup);
  }
}
