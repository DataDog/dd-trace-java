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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    private List<LibraryLoadingListener> listeners = new ArrayList<>();

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

    /** Uses a flat directory layout -- {@link LibraryResolvers#flatDirs()} */
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

    public Builder addListener(LibraryLoadingListener listener) {
      this.listeners.add(listener);
      return this;
    }

    public Builder addListeners(LibraryLoadingListener... listeners) {
      this.listeners.addAll(Arrays.asList(listeners));
      return this;
    }

    /** Constructs and returns the {@link NativeLoader} */
    public NativeLoader build() {
      return new NativeLoader(this);
    }

    PlatformSpec platformSpec() {
      return (this.platformSpec == null) ? PlatformSpec.defaultPlatformSpec() : this.platformSpec;
    }

    PathLocator pathLocator() {
      return (this.pathLocator == null) ? PathLocators.defaultPathLocator() : this.pathLocator;
    }

    SafeLibraryLoadingListener listeners() {
      return this.listeners.isEmpty()
          ? NopLibraryLoadingListener.INSTANCE
          : new CompositeLibraryLoadingListener(this.listeners);
    }

    LibraryResolver libResolver() {
      LibraryResolver baseResolver =
          (this.libResolver == null) ? LibraryResolvers.defaultLibraryResolver() : this.libResolver;

      return (this.preloadedLibNames == null)
          ? baseResolver
          : LibraryResolvers.withPreloaded(baseResolver, this.preloadedLibNames);
    }

    Path tempDir() {
      return this.tempDir;
    }
  }

  public static final Builder builder() {
    return new Builder();
  }

  private static final URL NO_URL = null;
  private static final Throwable NO_CAUSE = null;
  private static final LibraryLoadingListener[] EMPTY_LISTENERS = {};

  private final PlatformSpec defaultPlatformSpec;
  private final LibraryResolver libResolver;
  private final PathLocator pathResolver;
  private final SafeLibraryLoadingListener listeners;
  private final Path tempDir;

  private NativeLoader(Builder builder) {
    this.defaultPlatformSpec = builder.platformSpec();
    this.libResolver = builder.libResolver();
    this.pathResolver = builder.pathLocator();
    this.listeners = builder.listeners();
    this.tempDir = builder.tempDir();
  }

  public boolean isPlatformSupported() {
    return !this.defaultPlatformSpec.isUnknownOs() && !this.defaultPlatformSpec.isUnknownOs();
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
    this.loadImpl(null, libName, EMPTY_LISTENERS);
  }

  public void load(String libName, LibraryLoadingListener... scopedListeners)
      throws LibraryLoadException {
    this.loadImpl(null, libName, scopedListeners);
  }

  /** Loads a library associated with an associated component */
  public void load(String component, String libName) throws LibraryLoadException {}

  private void loadImpl(String component, String libName, LibraryLoadingListener... scopedListeners)
      throws LibraryLoadException {

    // scopedListeners are attached to the LibFile by resolveDynamicImpl
    try (LibFile libFile =
        this.resolveDynamicImpl(this.defaultPlatformSpec, component, libName, scopedListeners)) {
      libFile.load();
    }
  }

  /** Resolves a library to a LibFile - creating a temporary file if necessary */
  public LibFile resolveDynamic(String libName) throws LibraryLoadException {
    return this.resolveDynamicImpl(this.defaultPlatformSpec, null, libName);
  }

  public LibFile resolveDynamic(String libName, LibraryLoadingListener... scopedListeners)
      throws LibraryLoadException {
    return this.resolveDynamicImpl(this.defaultPlatformSpec, null, libName, scopedListeners);
  }

  /** Resolves a library with an associated component */
  public LibFile resolveDynamic(String component, String libName) throws LibraryLoadException {
    return this.resolveDynamicImpl(this.defaultPlatformSpec, component, libName);
  }

  /**
   * Resolves a library using a different {@link PlatformSpec} than the default for this {@link
   * NativeLoader}
   */
  public LibFile resolveDynamic(PlatformSpec platformSpec, String libName)
      throws LibraryLoadException {
    return this.resolveDynamicImpl(platformSpec, null, libName);
  }

  public LibFile resolveDynamic(
      PlatformSpec platformSpec, String libName, LibraryLoadingListener... scopedListeners)
      throws LibraryLoadException {
    return this.resolveDynamicImpl(platformSpec, null, libName, scopedListeners);
  }

  /**
   * Resolves a library with an associated component with a different {@link PlatformSpec} than the
   * default
   */
  public LibFile resolveDynamic(PlatformSpec platformSpec, String component, String libName)
      throws LibraryLoadException {
    return this.resolveDynamicImpl(platformSpec, component, libName);
  }

  private LibFile resolveDynamicImpl(
      PlatformSpec platformSpec, String optionalComponent, String libName)
      throws LibraryLoadException {
    return this.resolveDynamicImpl(platformSpec, optionalComponent, libName, EMPTY_LISTENERS);
  }

  private LibFile resolveDynamicImpl(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      LibraryLoadingListener... scopedListeners)
      throws LibraryLoadException {
    SafeLibraryLoadingListener allListeners =
        (scopedListeners == null
                || scopedListeners == EMPTY_LISTENERS
                || scopedListeners.length == 0)
            ? this.listeners
            : this.listeners.join(scopedListeners);

    if (platformSpec.isUnknownOs() || platformSpec.isUnknownArch()) {
      allListeners.onResolveDynamicFailure(platformSpec, optionalComponent, libName, NO_CAUSE);
      throw new LibraryLoadException(libName, "Unsupported platform");
    }

    boolean isPreloaded = this.isPreloaded(platformSpec, libName);
    if (isPreloaded) {
      allListeners.onResolveDynamic(platformSpec, optionalComponent, libName, isPreloaded, NO_URL);
      return LibFile.preloaded(platformSpec, optionalComponent, libName, allListeners);
    }

    URL url;
    try {
      url = this.libResolver.resolve(this.pathResolver, optionalComponent, platformSpec, libName);
    } catch (LibraryLoadException e) {
      // don't wrap if it is already a LibraryLoadException
      allListeners.onResolveDynamicFailure(platformSpec, optionalComponent, libName, e.getCause());
      throw e;
    } catch (Throwable t) {
      allListeners.onResolveDynamicFailure(platformSpec, optionalComponent, libName, t);
      throw new LibraryLoadException(libName, t);
    }

    if (url == null) {
      LibraryLoadException ex = new LibraryLoadException(libName);
      allListeners.onResolveDynamicFailure(platformSpec, optionalComponent, libName, ex);
      throw ex;
    }

    // For listener purposes - at this point resolution completed successfully
    // Although, the resolveDynamic method can still fail if we need a temp file and cannot create
    // it
    allListeners.onResolveDynamic(platformSpec, optionalComponent, libName, isPreloaded, url);

    if (url.getProtocol().equals("file")) {
      return LibFile.fromFile(
          platformSpec, optionalComponent, libName, new File(url.getPath()), allListeners);
    } else {
      Path tempFile;
      try {
        tempFile = createTempFile(this.tempDir, platformSpec, libName, url);
        allListeners.onTempFileCreated(platformSpec, optionalComponent, libName, tempFile);

        return LibFile.fromTempFile(
            platformSpec, optionalComponent, libName, tempFile.toFile(), allListeners);
      } catch (Throwable t) {
        allListeners.onTempFileCreationFailure(
            platformSpec, optionalComponent, libName, this.tempDir, libName, null, t);

        throw new LibraryLoadException(libName, t);
      }
    }
  }

  private static Path createTempFile(
      Path tempDir, PlatformSpec platformSpec, String libName, URL url) throws IOException {
    String libExt = PathUtils.dynamicLibExtension(platformSpec);

    Path tempFile = TempFileHelper.createTempFile(tempDir, libName, libExt);
    try (InputStream in = url.openStream()) {
      Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
    }

    return tempFile;
  }

  static boolean delete(File tempFile) {
    return TempFileHelper.delete(tempFile);
  }

  static final class TempFileHelper {
    private TempFileHelper() {}

    static Path createTempFile(Path tempDir, String libname, String libExt)
        throws IOException, SecurityException {
      FileAttribute<Set<PosixFilePermission>> permAttrs =
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

      if (tempDir == null) {
        return Files.createTempFile(libname, "." + libExt, permAttrs);
      } else {
        Files.createDirectories(
            tempDir,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));

        return Files.createTempFile(tempDir, libname, "." + libExt, permAttrs);
      }
    }

    static boolean delete(File tempFile) {
      boolean deleted = tempFile.delete();
      if (!deleted) tempFile.deleteOnExit();
      return deleted;
    }
  }
}
