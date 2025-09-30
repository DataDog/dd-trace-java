package datadog.nativeloader;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;

/** LibDirBasedPathLocator locates libraries inside a list of library directories */
public final class LibDirBasedPathLocator implements PathLocator {
  private final File[] libDirs;

  public LibDirBasedPathLocator(File... libDirs) {
    this.libDirs = libDirs;
  }

  @Override
  public URL locate(String component, String path) {
    String fullPath = component == null ? path : component + "/" + path;

    for (File libDir : this.libDirs) {
      File libFile = new File(libDir, fullPath);
      if (libFile.exists()) return toUrl(libFile);
    }

    return null;
  }

  @SuppressWarnings("deprecation")
  private static final URL toUrl(File file) {
    try {
      return file.toURL();
    } catch (MalformedURLException e) {
      return null;
    }
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(this.libDirs);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof LibDirBasedPathLocator)) return false;

    LibDirBasedPathLocator that = (LibDirBasedPathLocator) obj;
    return Arrays.equals(this.libDirs, that.libDirs);
  }
}
