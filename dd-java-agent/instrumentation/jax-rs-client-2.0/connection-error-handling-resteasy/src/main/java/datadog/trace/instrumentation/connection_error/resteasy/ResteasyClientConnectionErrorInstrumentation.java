package datadog.trace.instrumentation.connection_error.resteasy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.jaxrs.ClientTracingFilter;
import java.util.concurrent.Future;
import net.bytebuddy.asm.Advice;
import org.jboss.resteasy.client.jaxrs.internal.ClientConfiguration;

/**
 * JAX-RS Client API doesn't define a good point where we can handle connection failures, so we must
 * handle these errors at the implementation level.
 */
@AutoService(InstrumenterModule.class)
public final class ResteasyClientConnectionErrorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ResteasyClientConnectionErrorInstrumentation() {
    super("jax-rs", "jaxrs", "jax-rs-client");
  }

  @Override
  public String instrumentedType() {
    return "org.jboss.resteasy.client.jaxrs.internal.ClientInvocation";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".WrappedFuture",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("invoke")),
        ResteasyClientConnectionErrorInstrumentation.class.getName() + "$InvokeAdvice");

    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("submit")).and(returns(Future.class)),
        ResteasyClientConnectionErrorInstrumentation.class.getName() + "$SubmitAdvice");
  }

  public static class InvokeAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void handleError(
        @Advice.FieldValue("configuration") final ClientConfiguration context,
        @Advice.Thrown final Throwable throwable) {
      if (throwable != null) {
        final Object prop = context.getProperty(ClientTracingFilter.SPAN_PROPERTY_NAME);
        if (prop instanceof AgentSpan) {
          final AgentSpan span = (AgentSpan) prop;
          span.addThrowable(throwable);

          @SuppressWarnings("deprecation")
          final boolean isJaxRsExceptionAsErrorEnabled =
              Config.get().isJaxRsExceptionAsErrorEnabled();
          span.setError(isJaxRsExceptionAsErrorEnabled);

          span.finish();
        }
      }
    }
  }

  public static class SubmitAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void handleError(
        @Advice.FieldValue("configuration") final ClientConfiguration context,
        @Advice.Return(readOnly = false) Future<?> future) {
      if (!(future instanceof WrappedFuture)) {
        future = new WrappedFuture<>(future, context);
      }
    }
  }
}
