package datadog.trace.agent.tooling;

import java.util.BitSet;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Selects the transformations explicitly registered for a generated lambda interface. */
final class LambdaMatchRecorder {
  private final ElementMatcher<TypeDescription> typeMatcher;
  private final ElementMatcher<ClassLoader> classLoaderMatcher;
  private final BitSet transformationIds = new BitSet();

  LambdaMatchRecorder(
      int transformationId,
      ElementMatcher<TypeDescription> typeMatcher,
      ElementMatcher<ClassLoader> classLoaderMatcher) {
    this.typeMatcher = typeMatcher;
    this.classLoaderMatcher = classLoaderMatcher;
    transformationIds.set(transformationId);
  }

  void addTransformation(int transformationId) {
    transformationIds.set(transformationId);
  }

  void record(TypeDescription type, ClassLoader classLoader, BitSet matches) {
    if (classLoaderMatcher.matches(classLoader) && typeMatcher.matches(type)) {
      matches.or(transformationIds);
    }
  }
}
