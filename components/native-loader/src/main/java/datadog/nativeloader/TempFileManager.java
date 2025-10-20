package datadog.nativeloader;

import java.io.IOException;
import java.nio.file.Path;

public interface TempFileManager {
  boolean checkTempFolder();

  Path createTempFile(String libName, String libExt) throws IOException, SecurityException;
}
