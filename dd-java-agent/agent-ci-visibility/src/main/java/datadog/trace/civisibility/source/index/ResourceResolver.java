package datadog.trace.civisibility.source.index;

import java.io.IOException;
import java.nio.file.Path;
import javax.annotation.Nullable;

public interface ResourceResolver {
  @Nullable
  Path getResourceRoot(Path resourceFile) throws IOException;
}
