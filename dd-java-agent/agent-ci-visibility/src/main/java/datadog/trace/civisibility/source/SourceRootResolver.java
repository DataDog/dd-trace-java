package datadog.trace.civisibility.source;

import java.io.IOException;
import java.nio.file.Path;

public interface SourceRootResolver {
  Path getSourceRoot(Path sourceFile) throws IOException;
}
