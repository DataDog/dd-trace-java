package datadog.trace.civisibility.source.index;

import java.io.IOException;
import java.nio.file.Path;

public interface ResourceResolver {
  Path getResourceRoot(Path resourceFile) throws IOException;
}
