package datadog.nativeloader;

import java.net.URL;

/**
 * NestedDirLibraryResolver - uses nested directories to provide more specific libraries to load
 * {os} / {arch} / {libc}
 */
public final class NestedDirLibraryResolver implements LibraryResolver {
  public static final NestedDirLibraryResolver INSTANCE = new NestedDirLibraryResolver();

  @Override
  public final URL resolve(
      PathLocator pathResolver, String component, PlatformSpec platformSpec, String libName) {
    String libFileName = PathUtils.libFileName(platformSpec, libName);

    String osPath = PathUtils.osPartOf(platformSpec);
    String archPath = PathUtils.archPartOf(platformSpec);

    String libcPath = PathUtils.libcPartOf(platformSpec);

    URL url;
    String regularPath = osPath + "/" + archPath;

    if (libcPath != null) {
      String specializedPath = regularPath + "/" + libcPath;
      url = pathResolver.locate(component, specializedPath + "/" + libFileName);
      if (url != null) return url;
    }

    url = pathResolver.locate(component, regularPath + "/" + libFileName);
    if (url != null) return url;

    // fallback to searching at top-level, mostly concession to good out-of-box behavior
    // with java.library.path
    url = pathResolver.locate(component, libFileName);
    if (url != null) return url;

    if (component != null) {
      url = pathResolver.locate(null, libFileName);
      if (url != null) return url;
    }

    return null;
  }
}
