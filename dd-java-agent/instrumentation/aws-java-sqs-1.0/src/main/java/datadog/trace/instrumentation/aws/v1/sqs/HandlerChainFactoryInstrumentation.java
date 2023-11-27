package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.handlers.RequestHandler2;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class HandlerChainFactoryInstrumentation extends AbstractSqsInstrumentation
    implements Instrumenter.ForSingleType {

  @Override
  public String instrumentedType() {
    return "com.amazonaws.handlers.HandlerChainFactory";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DsmRequestHandler", packageName + ".MessageAttributeInjector",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.amazonaws.AmazonWebServiceRequest",
        "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("newRequestHandler2Chain")),
        HandlerChainFactoryInstrumentation.class.getName() + "$HandlerChainAdvice");
  }

  public static class HandlerChainAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addHandler(@Advice.Return final List<RequestHandler2> handlers) {
      if (Config.get().isDataStreamsEnabled()) {
        for (final RequestHandler2 handler : handlers) {
          if (handler instanceof DsmRequestHandler) {
            return;
          }
        }
        handlers.add(
            new DsmRequestHandler(
                InstrumentationContext.get(
                    "com.amazonaws.AmazonWebServiceRequest",
                    "datadog.trace.bootstrap.instrumentation.api.AgentSpan")));
      }
    }
  }
}
