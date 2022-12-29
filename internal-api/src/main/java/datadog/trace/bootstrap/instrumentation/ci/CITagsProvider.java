package datadog.trace.bootstrap.instrumentation.ci;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Map;

@SuppressForbidden
public interface CITagsProvider {
  boolean isCI();

  Map<String, String> getCiTags();
}
