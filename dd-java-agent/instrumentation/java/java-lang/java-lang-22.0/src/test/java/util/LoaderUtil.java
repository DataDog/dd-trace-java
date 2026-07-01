package util;

import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;

/**
 * Groovy is doing odd things and System.load needs to have a stable caller to pin to the right
 * classloader
 */
public final class LoaderUtil {
  private LoaderUtil() {}

  public static void loadLibrary(Path path) {
    System.load(path.toString());
  }

  public static SymbolLookup loaderLookup() {
    return SymbolLookup.loaderLookup();
  }
}
