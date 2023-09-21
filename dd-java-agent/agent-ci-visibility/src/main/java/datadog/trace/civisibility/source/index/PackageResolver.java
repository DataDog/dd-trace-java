package datadog.trace.civisibility.source.index;

import java.io.IOException;
import java.nio.file.Path;

public interface PackageResolver {
  Path getPackage(Path sourceFile) throws IOException;
}
