package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import java.util.List;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

/** AWS SDK v2 instrumentation */
@AutoService(Instrumenter.class)
public final class SqsClientInstrumentation extends AbstractSqsClientInstrumentation
    implements Instrumenter.ForSingleType {

  @Override
  public String instrumentedType() {
    return "software.amazon.awssdk.core.client.builder.SdkDefaultClientBuilder";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("resolveExecutionInterceptors")),
        SqsClientInstrumentation.class.getName() + "$AwsSqsBuilderAdvice");
  }

  public static class AwsSqsBuilderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void methodExit(@Advice.Return final List<ExecutionInterceptor> interceptors) {
      if (Config.get().isDataStreamsEnabled()) {
        for (ExecutionInterceptor interceptor : interceptors) {
          if (interceptor instanceof SqsInterceptor) {
            return; // list already has our interceptor, return to builder
          }
        }
        interceptors.add(new SqsInterceptor());
      }
    }
  }
}
