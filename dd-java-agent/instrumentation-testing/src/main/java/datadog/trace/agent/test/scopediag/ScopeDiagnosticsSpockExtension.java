package datadog.trace.agent.test.scopediag;

import org.spockframework.runtime.extension.IAnnotationDrivenExtension;
import org.spockframework.runtime.extension.IMethodInterceptor;
import org.spockframework.runtime.model.MethodInfo;
import org.spockframework.runtime.model.SpecInfo;

/** Preserves suite-setup diagnostics when a Spock {@code setupSpec()} method fails. */
public final class ScopeDiagnosticsSpockExtension
    implements IAnnotationDrivenExtension<TrackScopeContinuations> {
  private static final IMethodInterceptor SETUP_SPEC_INTERCEPTOR =
      invocation -> {
        try {
          invocation.proceed();
        } catch (Throwable setupFailure) {
          Object sharedInstance = invocation.getSharedInstance();
          if (sharedInstance == null) {
            sharedInstance = invocation.getTarget();
          }
          if (sharedInstance instanceof ScopeDiagnosticsSpockSupport) {
            try {
              ((ScopeDiagnosticsSpockSupport) sharedInstance).onSuiteSetupFailure();
            } catch (Throwable diagnosticFailure) {
              setupFailure.addSuppressed(diagnosticFailure);
            }
          }
          throw setupFailure;
        }
      };

  @Override
  public void visitSpecAnnotation(TrackScopeContinuations annotation, SpecInfo spec) {
    for (MethodInfo setupSpec : spec.getBottomSpec().getAllSetupSpecMethods()) {
      if (!setupSpec.getInterceptors().contains(SETUP_SPEC_INTERCEPTOR)) {
        setupSpec.addInterceptor(SETUP_SPEC_INTERCEPTOR);
      }
    }
  }
}
