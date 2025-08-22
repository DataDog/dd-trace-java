package datadog.trace.instrumentation.aws.v1.sns;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.handlers.RequestHandler2;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/** AWS SDK v1 SNS instrumentation */
@AutoService(InstrumenterModule.class)
public final class SnsClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SnsClientInstrumentation() {
    super("sns", "aws-sdk");
  }

  @Override
  public String instrumentedType() {
    return "com.amazonaws.handlers.HandlerChainFactory";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("newRequestHandler2Chain")),
        SnsClientInstrumentation.class.getName() + "$HandlerChainAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".SnsInterceptor", packageName + ".TextMapInjectAdapter"};
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.amazonaws.AmazonWebServiceRequest", "datadog.context.Context");
  }

  public static class HandlerChainAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addHandler(@Advice.Return final List<RequestHandler2> handlers) {
      for (RequestHandler2 interceptor : handlers) {
        if (interceptor instanceof SnsInterceptor) {
          return; // list already has our interceptor, return to builder
        }
      }
      handlers.add(
          new SnsInterceptor(
              InstrumentationContext.get(
                  "com.amazonaws.AmazonWebServiceRequest", "datadog.context.Context")));
    }
  }
}
