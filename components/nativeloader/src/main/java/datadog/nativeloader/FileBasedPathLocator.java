package datadog.nativeloader;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Objects;

public final class FileBasedPathLocator implements PathLocator {
  private final File[] libDirs;
  
  public FileBasedPathLocator(File... libDirs) {
    this.libDirs = libDirs;
  }
  
  @Override
  public URL locate(String component, String path) {
    String fullPath = component == null ? path : component + "/" + path;
    
    for ( File libDir: this.libDirs ) {
      File libFile = new File(libDir, fullPath);
      if ( libFile.exists() ) return toUrl(libFile);
    }
    
    return null;
  }
  
  @SuppressWarnings("deprecation")
  private static final URL toUrl(File file) {
    try {
      return file.toURL();
    } catch ( MalformedURLException e ) {
      return null;
    }
  }
  
  @Override
  public int hashCode() {
	return Objects.hash(this.libDirs);
  }
  
  @Override
  public boolean equals(Object obj) {
	if (!(obj instanceof FileBasedPathLocator)) return false;
	
	FileBasedPathLocator that = (FileBasedPathLocator)obj;
	return Arrays.equals(this.libDirs, that.libDirs);
  }
}