package datadog.nativeloader;

import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class LibraryResolvers {
  private LibraryResolvers() {}

  public static final LibraryResolver defaultLibraryResolver() {
    return flatDirs();
  }

  public static final LibraryResolver withPreloaded(
      LibraryResolver baseResolver, String... preloadedLibNames) {
    return withPreloaded(baseResolver, new HashSet<>(Arrays.asList(preloadedLibNames)));
  }

  public static final LibraryResolver withPreloaded(
      LibraryResolver baseResolver, Set<String> preloadedLibNames) {
    return new LibraryResolver() {
      @Override
      public boolean isPreloaded(PlatformSpec platform, String libName) {
        return preloadedLibNames.contains(libName);
      }

      @Override
      public URL resolve(
          PathLocator pathLocator, PlatformSpec platformSpec, String optionalComponent, String libName)
          throws Exception {
        return baseResolver.resolve(pathLocator, platformSpec, optionalComponent, libName);
      }
    };
  }

  public static final LibraryResolver flatDirs() {
    return FlatDirLibraryResolver.INSTANCE;
  }

  public static final LibraryResolver nestedDirs() {
    return NestedDirLibraryResolver.INSTANCE;
  }
}
