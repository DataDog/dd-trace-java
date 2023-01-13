package datadog.trace.bootstrap.instrumentation.ci;

import java.util.Map;

public interface CITagsProvider {
  boolean isCI();

  Map<String, String> getCiTags();
}
