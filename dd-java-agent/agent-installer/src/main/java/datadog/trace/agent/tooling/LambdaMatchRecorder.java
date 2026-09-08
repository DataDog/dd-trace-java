package datadog.trace.agent.tooling;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Selects the transformations explicitly registered for a generated lambda interface. */
final class LambdaMatchRecorder {
  private static final Logger log = LoggerFactory.getLogger(LambdaMatchRecorder.class);

  private final ElementMatcher<TypeDescription> typeMatcher;
  private final ElementMatcher<ClassLoader> classLoaderMatcher;
  private final BitSet transformationIds = new BitSet();
  private final List<ConditionalTransformation> conditionalTransformations = new ArrayList<>();

  LambdaMatchRecorder(
      int transformationId,
      ElementMatcher<TypeDescription> typeMatcher,
      ElementMatcher<ClassLoader> classLoaderMatcher) {
    this.typeMatcher = typeMatcher;
    this.classLoaderMatcher = classLoaderMatcher;
    transformationIds.set(transformationId);
  }

  void addTransformation(
      int transformationId, ElementMatcher<TypeDescription> matcher, String matcherDescription) {
    conditionalTransformations.add(
        new ConditionalTransformation(transformationId, matcher, matcherDescription));
  }

  void record(TypeDescription type, ClassLoader classLoader, BitSet matches) {
    if (classLoaderMatcher.matches(classLoader) && typeMatcher.matches(type)) {
      matches.or(transformationIds);
      for (ConditionalTransformation transformation : conditionalTransformations) {
        try {
          if (transformation.matcher.matches(type)) {
            matches.set(transformation.id);
          }
        } catch (Throwable e) {
          if (log.isDebugEnabled()) {
            log.debug(
                "Lambda transformation matcher unexpected exception - {}",
                transformation.description,
                e);
          }
        }
      }
    }
  }

  String describe() {
    return typeMatcher.toString();
  }

  private static final class ConditionalTransformation {
    private final int id;
    private final ElementMatcher<TypeDescription> matcher;
    private final String description;

    private ConditionalTransformation(
        int id, ElementMatcher<TypeDescription> matcher, String description) {
      this.id = id;
      this.matcher = matcher;
      this.description = description;
    }
  }
}
