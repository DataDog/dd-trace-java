package datadog.trace.civisibility;

import java.util.Map;

public interface CITagsProvider {

  Map<String, String> getCiTags();
}
