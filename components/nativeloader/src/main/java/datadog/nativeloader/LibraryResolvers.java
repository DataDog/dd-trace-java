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
  
  public static final LibraryResolver withPreloaded(LibraryResolver baseResolver, String... preloadedLibNames) {
	Set<String> preloadedSet = new HashSet<>(Arrays.asList(preloadedLibNames));
	return new LibraryResolver() {
	  @Override
	  public boolean isPreloaded(PlatformSpec platform, String libName) {
		return preloadedSet.contains(libName);
	  }
	  
	  @Override
	  public URL resolve(PathLocator pathLocator, String component, PlatformSpec platformSpec, String libName) {
		return baseResolver.resolve(pathLocator, component, platformSpec, libName);
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
