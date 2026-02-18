package datadog.nativeloader;

import java.net.URL;

/**
 * FlatDirLibraryResolver - uses flat directories to provide more specific libraries to load <code>
 * {os}-{arch}-{libc/musl}</code>
 */
public final class FlatDirLibraryResolver implements LibraryResolver {
  public static final FlatDirLibraryResolver INSTANCE = new FlatDirLibraryResolver();

  private FlatDirLibraryResolver() {}

  @Override
  public final URL resolve(
      PathLocator pathLocator, PlatformSpec platformSpec, String optionalComponent, String libName)
      throws Exception {
    PathLocatorHelper pathLocatorHelper = new PathLocatorHelper(libName, pathLocator);

    String libFileName = PathUtils.libFileName(platformSpec, libName);

    String osPath = PathUtils.osPartOf(platformSpec);
    String archPath = PathUtils.archPartOf(platformSpec);
    String libcPath = PathUtils.libcPartOf(platformSpec);

    URL url;
    String regularPath = osPath + "-" + archPath;

    if (libcPath != null) {
      String specializedPath = regularPath + "-" + libcPath;
      url = pathLocatorHelper.locate(optionalComponent, specializedPath + "/" + libFileName);
      if (url != null) return url;
    }

    url = pathLocatorHelper.locate(optionalComponent, regularPath + "/" + libFileName);
    if (url != null) return url;

    url = pathLocatorHelper.locate(optionalComponent, osPath + "/" + libFileName);
    if (url != null) return url;

    // fallback to searching at top-level, mostly concession to good out-of-box behavior
    // with java.library.path
    url = pathLocatorHelper.locate(optionalComponent, libFileName);
    if (url != null) return url;

    if (optionalComponent != null) {
      url = pathLocatorHelper.locate(null, libFileName);
      if (url != null) return url;
    }

    pathLocatorHelper.tryThrow();

    return null;
  }
}
