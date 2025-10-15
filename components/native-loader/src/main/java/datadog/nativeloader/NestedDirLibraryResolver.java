package datadog.nativeloader;

import java.net.URL;

/**
 * NestedDirLibraryResolver - uses nested directories to provide more specific libraries to load
 * <code>{os} / {arch} / {libc}</code>
 */
final class NestedDirLibraryResolver implements LibraryResolver {
  public static final NestedDirLibraryResolver INSTANCE = new NestedDirLibraryResolver();

  @Override
  public final URL resolve(
      PathLocator pathLocator, String component, PlatformSpec platformSpec, String libName)
      throws Exception {
    PathLocatorHelper pathLocatorHelper = new PathLocatorHelper(libName, pathLocator);

    String libFileName = PathUtils.libFileName(platformSpec, libName);

    String osPath = PathUtils.osPartOf(platformSpec);
    String archPath = PathUtils.archPartOf(platformSpec);

    String libcPath = PathUtils.libcPartOf(platformSpec);

    URL url;
    String regularPath = osPath + "/" + archPath;

    if (libcPath != null) {
      String specializedPath = regularPath + "/" + libcPath;
      url = pathLocatorHelper.locate(component, specializedPath + "/" + libFileName);
      if (url != null) return url;
    }

    url = pathLocatorHelper.locate(component, regularPath + "/" + libFileName);
    if (url != null) return url;

    url = pathLocatorHelper.locate(component, osPath + "/" + libFileName);
    if (url != null) return url;

    // fallback to searching at top-level, mostly concession to good out-of-box behavior
    // with java.library.path
    url = pathLocatorHelper.locate(component, libFileName);
    if (url != null) return url;

    if (component != null) {
      url = pathLocatorHelper.locate(null, libFileName);
      if (url != null) return url;
    }

    pathLocatorHelper.tryThrow();

    return null;
  }
}
