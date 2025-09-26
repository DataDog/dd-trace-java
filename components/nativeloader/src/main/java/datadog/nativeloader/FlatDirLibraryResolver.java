package datadog.nativeloader;

import java.net.URL;

/**
/**
 * FlatDirLibraryResolver - uses flat directories to provide more specific libraries to load
 * {os}-{arch}-{libc/musl}
 */
 */
public final class FlatDirLibraryResolver implements LibraryResolver {
  public static final LibraryResolver INSTANCE = new FlatDirLibraryResolver();

  private FlatDirLibraryResolver() {}

  @Override
  public final URL resolve(
      PathLocator pathLocator, String component, PlatformSpec platformSpec, String libName) {
    String libFileName = PathUtils.libFileName(platformSpec, libName);

    String osPath = PathUtils.osPartOf(platformSpec);
    String archPath = PathUtils.archPartOf(platformSpec);
    String libcPath = PathUtils.libcPartOf(platformSpec);

    URL url;
    String regularPath = osPath + "-" + archPath;

    if (libcPath != null) {
      String specializedPath = regularPath + "-" + libcPath;
      url = pathLocator.locate(component, specializedPath + "/" + libFileName);
      if (url != null) return url;
    }

    url = pathLocator.locate(component, regularPath + "/" + libFileName);
    if (url != null) return url;

    // fallback to searching at top-level, mostly concession to good out-of-box behavior
    // with java.library.path
    url = pathLocator.locate(component, libFileName);
    if (url != null) return url;

    if (component != null) {
      url = pathLocator.locate(null, libFileName);
      if (url != null) return url;
    }

    return null;
  }
}
