package datadog.trace.instrumentation.aws.v2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import software.amazon.awssdk.core.http.ExecutionContext;

/** AWS SDK v2 instrumentation */
@AutoService(Instrumenter.class)
public final class AsyncAfterTransmissionInterceptorCallingResponseHandlerInstrumentation
    extends AbstractAwsClientInstrumentation {

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(
        "software.amazon.awssdk.core.internal.http.async.AsyncAfterTransmissionInterceptorCallingResponseHandler");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("onHeaders")), getClass().getName() + "$OnHeadersAdvice");
  }

  public static final class OnHeadersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan methodEnter(
        @Advice.FieldValue("context") final ExecutionContext context) {
      final AgentSpan span =
          context.executionAttributes().getAttribute(TracingExecutionInterceptor.SPAN_ATTRIBUTE);
      if (span != null) {
        span.finishThreadMigration();
      }
      return span;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(@Advice.Enter final AgentSpan span) {
      if (span != null) {
        span.startThreadMigration();
      }
    }

    /**
     * This is to make muzzle think we need TracingExecutionInterceptor to make sure we do not apply
     * this instrumentation when TracingExecutionInterceptor would not work.
     */
    public static void muzzleCheck() {
      TracingExecutionInterceptor.muzzleCheck();
    }
  }
}
