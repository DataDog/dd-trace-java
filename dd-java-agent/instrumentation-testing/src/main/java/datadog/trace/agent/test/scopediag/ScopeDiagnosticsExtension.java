package datadog.trace.agent.test.scopediag;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs {@link ScopeDiagnostics} around JUnit instrumentation tests and suite fixtures. */
public final class ScopeDiagnosticsExtension
    implements BeforeAllCallback,
        BeforeEachCallback,
        AfterEachCallback,
        AfterAllCallback,
        InvocationInterceptor {
  private static final Logger log = LoggerFactory.getLogger(ScopeDiagnosticsExtension.class);

  private boolean suiteEnabled;
  private boolean suiteSetupPending;

  @Override
  public void beforeAll(ExtensionContext context) {
    suiteEnabled = ScopeDiagnostics.isEnabled(resolveClass(context));
  }

  @Override
  public void interceptBeforeAllMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    invocation.proceed();
    if (suiteEnabled && isHarnessLifecycleMethod(invocationContext)) {
      ScopeDiagnostics.startRecording();
      suiteSetupPending = true;
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    if (suiteEnabled) {
      if (suiteSetupPending) {
        suiteSetupPending = false;
        report(context.getRequiredTestClass().getSimpleName() + " suite setup");
      } else {
        ScopeDiagnostics.reset();
      }
    }
    TrackScopeContinuations config = resolve(context);
    if (ScopeDiagnostics.isEnabled(config)) {
      ScopeDiagnostics.startRecording();
    }
  }

  @Override
  public void afterEach(ExtensionContext context) {
    try {
      TrackScopeContinuations config = resolve(context);
      if (ScopeDiagnostics.isEnabled(config)) {
        report(context.getDisplayName());
      }
    } finally {
      if (suiteEnabled) {
        ScopeDiagnostics.startRecording();
      }
    }
  }

  @Override
  public void interceptAfterAllMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    if (!suiteEnabled || !isHarnessLifecycleMethod(invocationContext)) {
      invocation.proceed();
      return;
    }
    try {
      report(extensionContext.getRequiredTestClass().getSimpleName() + " suite cleanup");
    } catch (Throwable diagnosticFailure) {
      try {
        invocation.proceed();
      } catch (Throwable cleanupFailure) {
        cleanupFailure.addSuppressed(diagnosticFailure);
        throw cleanupFailure;
      }
      throw diagnosticFailure;
    }
    invocation.proceed();
  }

  @Override
  public void afterAll(ExtensionContext context) {
    ScopeDiagnostics.reset();
  }

  private static boolean isHarnessLifecycleMethod(
      ReflectiveInvocationContext<Method> invocationContext) {
    return invocationContext.getExecutable().getDeclaringClass()
        == AbstractInstrumentationTest.class;
  }

  private static void report(String displayName) {
    try {
      ScopeDiagnostics.awaitQuiescence();
      ScopeDiagnostics.stop();
      ScopeDiagnosticsReport report = ScopeDiagnostics.report();
      if (report.hasFindings()) {
        log.info("[{}] {}", displayName, report.renderTimeline());
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

  private static TrackScopeContinuations resolveClass(ExtensionContext context) {
    return context
        .getTestClass()
        .flatMap(c -> AnnotationSupport.findAnnotation(c, TrackScopeContinuations.class))
        .orElse(null);
  }
}
