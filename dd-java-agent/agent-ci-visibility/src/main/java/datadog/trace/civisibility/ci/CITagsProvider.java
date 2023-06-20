package datadog.trace.civisibility.ci;

import java.nio.file.Path;
import java.util.Map;

public interface CITagsProvider {
  Map<String, String> getCiTags(Path path);
}
