package datadog.trace.agent.tooling.muzzle;

import java.util.List;

public interface IReferenceMatcher {
  boolean matches(ClassLoader loader);

  List<Reference.Mismatch> getMismatchedReferenceSources(ClassLoader loader);
}
