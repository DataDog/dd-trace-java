package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.handlers.RequestHandler2;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.List;
import net.bytebuddy.asm.Advice;

/**
 * This instrumentation might work with versions before 1.11.0, but this was the first version that
 * is tested. It could possibly be extended earlier.
 */
public final class HandlerChainFactoryInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private final String namespace;

  public HandlerChainFactoryInstrumentation(String namespace) {
    this.namespace = namespace;
  }

  @Override
  public String instrumentedType() {
    return namespace + ".handlers.HandlerChainFactory";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("newRequestHandler2Chain")),
        HandlerChainFactoryInstrumentation.class.getName() + "$HandlerChainAdvice");
  }

  public static class HandlerChainAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addHandler(@Advice.Return final List<RequestHandler2> handlers) {
      for (final RequestHandler2 handler : handlers) {
        if (handler instanceof TracingRequestHandler) {
          return;
        }
      }
      handlers.add(
          new TracingRequestHandler(
              InstrumentationContext.get(
                  "com.amazonaws.services.sqs.model.ReceiveMessageResult", "java.lang.String"),
              InstrumentationContext.get(
                  "com.amazonaws.AmazonWebServiceRequest", "datadog.context.Context")));
    }
  }
}
