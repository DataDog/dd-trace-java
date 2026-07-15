package datadog.trace.agent.test.scopediag;

import java.lang.reflect.AnnotatedElement;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit5 extension that enables {@link ScopeDiagnostics} for tests annotated with {@link
 * TrackScopeContinuations}. Registered on {@code AbstractInstrumentationTest} but dormant unless
 * the test class or method carries the annotation, so unannotated tests pay nothing.
 *
 * <p>Per test: resets and starts recording before; after, logs the full timeline and optionally
 * asserts no leaks.
 */
public final class ScopeDiagnosticsExtension implements BeforeEachCallback, AfterEachCallback {
  private static final Logger log = LoggerFactory.getLogger(ScopeDiagnosticsExtension.class);

  @Override
  public void beforeEach(ExtensionContext context) {
    if (resolve(context) != null) {
      ScopeDiagnostics.startRecording();
    }
  }

  @Override
  public void afterEach(ExtensionContext context) {
    TrackScopeContinuations config = resolve(context);
    if (config == null) {
      return;
    }
    ScopeDiagnostics.stop();
    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    // Always dump the full timeline so a graph/report can be built regardless of whether anything
    // leaked; renderSummary() remains the concise problem-only view used by assertNoLeaks.
    log.info("[{}] {}", context.getDisplayName(), report.renderTimeline());
    try {
      if (config.failOnLeak()) {
        ScopeDiagnostics.assertNoLeaks();
      }
    } finally {
      ScopeDiagnostics.reset();
    }
  }

  /** Method-level annotation wins; otherwise the test class (incl. inherited). */
  private static TrackScopeContinuations resolve(ExtensionContext context) {
    Optional<AnnotatedElement> element = context.getElement();
    if (element.isPresent()) {
      Optional<TrackScopeContinuations> onElement =
          AnnotationSupport.findAnnotation(element.get(), TrackScopeContinuations.class);
      if (onElement.isPresent()) {
        return onElement.get();
      }
    }
    return context
        .getTestClass()
        .flatMap(c -> AnnotationSupport.findAnnotation(c, TrackScopeContinuations.class))
        .orElse(null);
  }
}
