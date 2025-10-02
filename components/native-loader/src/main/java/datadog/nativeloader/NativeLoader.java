package datadog.nativeloader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * NativeLoader is intended as more feature rich replacement for calling {@link
 * System#loadLibrary(String)} directly. NativeLoader can be used to find the corresponding platform
 * specific library using pluggable strategies -- for both path determination {@link
 * LibraryResolver} and path resolution {@link PathLocator}
 */
public final class NativeLoader {
  public static final class Builder {
    private PlatformSpec platformSpec;
    private Path tempDir;
    private String[] preloadedLibNames;
    private LibraryResolver libResolver;
    private PathLocator pathLocator;

    Builder() {}

    /** Sets the default {@link PlatformSpec} used by the {@link NativeLoader} */
    public Builder platformSpec(PlatformSpec platform) {
      this.platformSpec = platform;
      return this;
    }

    /** Uses a nested directory layout -- {@link LibraryResolvers#nestedDirs()} */
    public Builder nestedLayout() {
      return this.libResolver(LibraryResolvers.nestedDirs());
    }

    /**
     * Uses a flat directory layout -- {@link LibraryResolvers#flatDirs()}
     */
    public Builder flatLayout() {
      return this.libResolver(LibraryResolvers.flatDirs());
    }

    /**
     * Indicates that libraries (or signatures from those libraries) are already loaded into the JVM
     * {@link LibraryResolver#isPreloaded}
     *
     * @param libNames - lib names
     */
    public Builder preloaded(String... libNames) {
      this.preloadedLibNames = libNames;
      return this;
    }

    /**
     * Uses the specified {@link LibraryResolver} can be used to implement an alternate file layout
     */
    public Builder libResolver(LibraryResolver libResolver) {
      this.libResolver = libResolver;
      return this;
    }

    /** Searches for the native libraries in the provided {@link ClassLoader} */
    public Builder fromClassLoader(ClassLoader classLoader) {
      return this.pathLocator(PathLocators.fromClassLoader(classLoader));
    }

    /**
     * Searches for the native libraries in the provided {@link ClassLoader} using the specified
     * <code>baseResource</code>
     */
    public Builder fromClassLoader(ClassLoader classLoader, String baseResource) {
      return this.pathLocator(PathLocators.fromClassLoader(classLoader, baseResource));
    }

    /** Searches for the native libraries in the specified directory */
    public Builder fromDir(String includeDir) {
      return this.pathLocator(PathLocators.fromLibDirs(includeDir));
    }

    /** Searches for the native libraries in the specified directories */
    public Builder fromDirs(String... includeDirs) {
      return this.pathLocator(PathLocators.fromLibDirs(includeDirs));
    }

    /** Searches for the native libraries in the specified directory */
    public Builder fromDir(File includeDir) {
      return this.pathLocator(PathLocators.fromLibDirs(includeDir));
    }

    /** Searches for the native libraries in the specified directories */
    public Builder fromDirs(File... includeDirs) {
      return this.pathLocator(PathLocators.fromLibDirs(includeDirs));
    }

    /** Searches for the native libraries in the specified directory */
    public Builder fromDir(Path includeDir) {
      return this.pathLocator(PathLocators.fromLibDirs(includeDir));
    }

    /** Searches for the native libraries in the specified directories */
    public Builder fromDirs(Path... paths) {
      return this.pathLocator(PathLocators.fromLibDirs(paths));
    }

    /** Searches for the native libraries using the provided {@link PathLocator} */
    public Builder pathLocator(PathLocator pathLocator) {
      this.pathLocator = pathLocator;
      return this;
    }

    /**
     * Specifies temporary directory where native libraries are copied if the {@link PathLocator}
     * returns a non-file {@link URL}
     */
    public Builder tempDir(File tempDir) {
      return this.tempDir(tempDir.toPath());
    }

    /**
     * Specifies temporary directory where native libraries are copied if the {@link PathLocator}
     * returns a non-file {@link URL}
     */
    public Builder tempDir(Path tempPath) {
      this.tempDir = tempPath;
      return this;
    }

    /**
     * Specifies temporary directory where native libraries are copied if the {@link PathLocator}
     * returns a non-file {@link URL}
     */
    public Builder tempDir(String tmpPath) {
      return this.tempDir(Paths.get(tmpPath));
    }

    /** Constructs and returns the {@link NativeLoader} */
    public NativeLoader build() {
      return new NativeLoader(this);
    }

    final PlatformSpec platformSpec() {
      return (this.platformSpec == null) ? PlatformSpec.defaultPlatformSpec() : this.platformSpec;
    }

    final PathLocator pathLocator() {
      return (this.pathLocator == null) ? PathLocators.defaultPathLocator() : this.pathLocator;
    }

    final LibraryResolver libResolver() {
      LibraryResolver baseResolver =
          (this.libResolver == null) ? LibraryResolvers.defaultLibraryResolver() : this.libResolver;

      return (this.preloadedLibNames == null)
          ? baseResolver
          : LibraryResolvers.withPreloaded(baseResolver, this.preloadedLibNames);
    }

    final Path tempDir() {
      return this.tempDir;
    }
  }

  public static final Builder builder() {
    return new Builder();
  }

  private final PlatformSpec defaultPlatformSpec;
  private final LibraryResolver libResolver;
  private final PathLocator pathResolver;
  private final Path tempDir;

  private NativeLoader(Builder builder) {
    this.defaultPlatformSpec = builder.platformSpec();
    this.libResolver = builder.libResolver();
    this.pathResolver = builder.pathLocator();
    this.tempDir = builder.tempDir();
  }

  /** Indicates if a library is considered "pre-loaded" */
  public boolean isPreloaded(String libName) {
    return this.libResolver.isPreloaded(this.defaultPlatformSpec, libName);
  }

  /** Indicates if a library is considered "pre-loaded" for the specified {@link PlatformSpec} */
  public boolean isPreloaded(PlatformSpec platformSpec, String libName) {
    return this.libResolver.isPreloaded(platformSpec, libName);
  }

  /** Loads a library */
  public void load(String libName) throws LibraryLoadException {
    this.load(null, libName);
  }

  /** Loads a library associated with an associated component */
  public final void load(String component, String libName) throws LibraryLoadException {
    try (LibFile libFile = this.resolveDynamic(component, libName)) {
      libFile.load();
    }
  }

  /** Resolves a library to a LibFile - creating a temporary file if necessary */
  public final LibFile resolveDynamic(String libName) throws LibraryLoadException {
    return this.resolveDynamic((String) null, libName);
  }

  /** Resolves a library with an associated component */
  public final LibFile resolveDynamic(String component, String libName)
      throws LibraryLoadException {
    return this.resolveDynamic(component, this.defaultPlatformSpec, libName);
  }

  /**
   * Resolves a library using a different {@link PlatformSpec} than the default for this {@link
   * NativeLoader}
   */
  public LibFile resolveDynamic(PlatformSpec platformSpec, String libName)
      throws LibraryLoadException {
    return this.resolveDynamic(null, platformSpec, libName);
  }

  /**
   * Resolves a library with an associated component with a different {@link PlatformSpec} than the
   * default
   */
  public LibFile resolveDynamic(String component, PlatformSpec platformSpec, String libName)
      throws LibraryLoadException {
    if (platformSpec.isUnknownOs() || platformSpec.isUnknownArch()) {
      throw new LibraryLoadException(libName, "Unsupported platform");
    }

    if (this.isPreloaded(platformSpec, libName)) {
      return LibFile.preloaded(libName);
    }

    URL url;
    try {
      url = this.libResolver.resolve(this.pathResolver, component, platformSpec, libName);
    } catch (LibraryLoadException e) {
      // don't wrap if it is already a LibraryLoadException
      throw e;
    } catch (Throwable t) {
      throw new LibraryLoadException(libName, t);
    }

    if (url == null) {
      throw new LibraryLoadException(libName);
    }
    return toLibFile(platformSpec, libName, url);
  }

  private final LibFile toLibFile(PlatformSpec platformSpec, String libName, URL url)
      throws LibraryLoadException {
    if (url.getProtocol().equals("file")) {
      return LibFile.fromFile(libName, new File(url.getPath()));
    } else {
      String libExt = PathUtils.dynamicLibExtension(platformSpec);

      try {
        Path tempFile = TempFileHelper.createTempFile(this.tempDir, libName, libExt);

        try (InputStream in = url.openStream()) {
          Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        return LibFile.fromTempFile(libName, tempFile.toFile());
      } catch (Throwable t) {
        throw new LibraryLoadException(libName, t);
      }
    }
  }

  static final void delete(File tempFile) {
    TempFileHelper.delete(tempFile);
  }

  static final class TempFileHelper {
    private TempFileHelper() {}

    static final Path createTempFile(Path tempDir, String libname, String libExt)
        throws IOException, SecurityException {
      FileAttribute<Set<PosixFilePermission>> permAttrs =
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

      if (tempDir == null) {
        return Files.createTempFile(libname, "." + libExt, permAttrs);
      } else {
        return Files.createTempFile(tempDir, libname, "." + libExt, permAttrs);
      }
    }

    static final void delete(File tempFile) {
      boolean deleted = tempFile.delete();
      if (!deleted) tempFile.deleteOnExit();
    }
  }
}
