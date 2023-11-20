package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.handlers.RequestHandler2;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.List;
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

  public static class HandlerChainAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addHandler(@Advice.Return final List<RequestHandler2> handlers) {
      for (RequestHandler2 interceptor : handlers) {
        if (interceptor instanceof SqsInterceptor) {
          return; // list already has our interceptor, return to builder
        }
      }
      handlers.add(new SqsInterceptor());
    }
  }
}
