package datadog.nativeloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public class SimpleTempFileManager implements TempFileManager {
  private final Path tempDir;

  public SimpleTempFileManager(Path tempDir) {
    this.tempDir = tempDir;
  }

  @Override
  public boolean checkTempFolder() {
    return true;
  }

  @Override
  public Path tempDir() {
    return tempDir;
  }

  @Override
  public Path createTempFile(String libName, String libExt) throws IOException, SecurityException {
    FileAttribute<Set<PosixFilePermission>> permAttrs =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    if (tempDir == null) {
      return Files.createTempFile(libName, "." + libExt, permAttrs);
    }

    Files.createDirectories(
        tempDir,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));

    return Files.createTempFile(tempDir, libName, "." + libExt, permAttrs);
  }
}
