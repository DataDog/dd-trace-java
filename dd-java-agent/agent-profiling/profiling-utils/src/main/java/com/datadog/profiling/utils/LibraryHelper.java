package com.datadog.profiling.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class LibraryHelper {
  /**
   * Locates a library on class-path (eg. in a JAR) and creates a publicly accessible temporary copy
   * of the library which can then be used by the application by its absolute path.
   *
   * @param path The resource path designating the library - must start with {@code '/'} (absolute
   *     path)
   * @return The library absolute path. The caller should properly dispose of the file once it is
   *     not needed. The file is marked for 'delete-on-exit' so it won't survive a JVM restart.
   * @throws IOException
   */
  public static File libraryFromClasspath(String path) throws IOException {

    if (!path.startsWith("/")) {
      throw new IllegalArgumentException("The path has to be absolute (start with '/').");
    }

    // Get the file name (the last part in path)
    File libPath = new File(path);
    String filename = libPath.getName();

    // Split filename to prexif and suffix (extension)
    String prefix = "";
    String suffix = null;
    int idx = filename.lastIndexOf('.');
    if (idx > -1) {
      prefix = filename.substring(0, idx);
      suffix = filename.substring(idx);
    } else {
      prefix = filename;
      suffix = null;
    }

    // Check if the filename is okay
    if (prefix.length() < 3) {
      throw new IllegalArgumentException("The filename expects at least 3 characters.");
    }

    // Prepare temporary file
    File temp = File.createTempFile(prefix, suffix);
    temp.deleteOnExit();

    // Prepare buffer for data copying
    byte[] buffer = new byte[8192];
    int readBytes;

    // Open and check input stream
    try (InputStream is = LibraryHelper.class.getResourceAsStream(path)) {
      if (is == null) {
        throw new FileNotFoundException("File " + path + " was not found on classpath.");
      }

      // Open output stream and copy data between source file in JAR and the temporary file
      try (OutputStream os = new FileOutputStream(temp)) {
        while ((readBytes = is.read(buffer)) != -1) {
          os.write(buffer, 0, readBytes);
        }
      }
    }
    return temp;
  }
}
