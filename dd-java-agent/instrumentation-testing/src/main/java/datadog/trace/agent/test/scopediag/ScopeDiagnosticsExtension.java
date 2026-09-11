package datadog.trace.agent.test.scopediag;

import java.lang.reflect.AnnotatedElement;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs {@link ScopeDiagnostics} around each JUnit instrumentation test. */
public final class ScopeDiagnosticsExtension implements BeforeEachCallback, AfterEachCallback {
  private static final Logger log = LoggerFactory.getLogger(ScopeDiagnosticsExtension.class);

  @Override
  public void beforeEach(ExtensionContext context) {
    TrackScopeContinuations config = resolve(context);
    if (ScopeDiagnostics.isEnabled(config)) {
      ScopeDiagnostics.startRecording();
    }
  }

  @Override
  public void afterEach(ExtensionContext context) {
    TrackScopeContinuations config = resolve(context);
    if (!ScopeDiagnostics.isEnabled(config)) {
      return;
    }
    try {
      ScopeDiagnostics.stop();
      ScopeDiagnosticsReport report = ScopeDiagnostics.report();
      if (report.hasFindings()) {
        log.info("[{}] {}", context.getDisplayName(), report.renderTimeline());
      }
      ScopeDiagnostics.assertNoLeaks(report);
    } finally {
      ScopeDiagnostics.reset();
    }
  }

  /** Resolves method configuration before inherited class configuration. */
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
