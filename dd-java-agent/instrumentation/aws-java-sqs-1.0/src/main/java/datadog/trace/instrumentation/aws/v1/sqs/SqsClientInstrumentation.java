package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.handlers.RequestHandler2;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/** AWS SDK v1 instrumentation */
@AutoService(Instrumenter.class)
public final class SqsClientInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  private static final String INSTRUMENTATION_NAME = "aws-sdk";

  public SqsClientInstrumentation() {
    super(INSTRUMENTATION_NAME);
  }

  @Override
  public String instrumentedType() {
    return "com.amazonaws.handlers.HandlerChainFactory";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("newRequestHandler2Chain")),
        SqsClientInstrumentation.class.getName() + "$HandlerChainAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SqsInterceptor", packageName + ".MessageAttributeInjector"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        AmazonWebServiceRequest.class.getName(), AgentSpan.class.getName());
  }

  public static class HandlerChainAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addHandler(@Advice.Return final List<RequestHandler2> handlers) {
      for (RequestHandler2 interceptor : handlers) {
        if (interceptor instanceof SqsInterceptor) {
          return; // list already has our interceptor, return to builder
        }
      }
      handlers.add(
          new SqsInterceptor(
              InstrumentationContext.get(AmazonWebServiceRequest.class, AgentSpan.class)));
    }
  }
}
