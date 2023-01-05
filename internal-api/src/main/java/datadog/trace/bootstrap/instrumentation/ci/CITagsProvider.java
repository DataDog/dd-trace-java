package datadog.trace.bootstrap.instrumentation.ci;

import java.util.Map;

public interface CITagsProvider {

  Map<String, String> getCiTags();
}
