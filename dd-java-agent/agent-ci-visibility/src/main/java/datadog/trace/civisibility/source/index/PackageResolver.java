package datadog.trace.civisibility.source.index;

import java.io.IOException;
import java.nio.file.Path;
import javax.annotation.Nullable;

public interface PackageResolver {
  /**
   * @return the package path or <code>null</code> if the file is in the default package
   */
  @Nullable
  Path getPackage(Path sourceFile) throws IOException;
}
