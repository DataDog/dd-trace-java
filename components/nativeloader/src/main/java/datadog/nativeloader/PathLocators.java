package datadog.nativeloader;

import java.io.File;
import java.nio.file.Path;

/**
 * Helper factory class for common {@link PathLocator}
 */
public final class PathLocators {
  private PathLocators() {}
  
  public static final PathLocator defaultPathLocator() {
    return fromJavaLibraryPath();
  }
  
  public static final PathLocator fromLibDirs(Path... libDirs) {
    File[] libDirFiles = new File[libDirs.length];
    for ( int i = 0; i < libDirs.length; ++i ) {
      libDirFiles[i] = libDirs[i].toFile();
    }
    return fromLibDirs(libDirFiles);
  }
  
  public static final PathLocator fromLibDirs(String... libDirs) {
    File[] libDirFiles = new File[libDirs.length];
    for ( int i = 0; i < libDirs.length; ++i ) {
      libDirFiles[i] = new File(libDirs[i]);
    }
    return fromLibDirs(libDirFiles);
  }

  public static final PathLocator fromLibDirs(File... libDirs) {
    return new FileBasedPathLocator(libDirs);
  }
  
  public static final PathLocator fromJavaLibraryPath() {
    String libPaths;
    try {
      libPaths = System.getProperty("java.library.path");
    } catch ( SecurityException e ) {
      return new FileBasedPathLocator();
    }
    return fromLibDirs(libPaths.split("\\:"));
  }
  
  public static final PathLocator fromClassLoader(ClassLoader classLoader) {
    return new ClassLoaderResourcePathLocator(classLoader, null); 
  }
  
  public static final PathLocator fromClassLoader(
    ClassLoader classLoader, String baseResource) {
    return new ClassLoaderResourcePathLocator(classLoader, baseResource); 
  }
}
