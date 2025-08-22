package datadog.trace.instrumentation.aws.v2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

/** AWS SDK v2 instrumentation */
@AutoService(InstrumenterModule.class)
public final class AwsClientInstrumentation extends AbstractAwsClientInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "software.amazon.awssdk.core.client.builder.SdkDefaultClientBuilder";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse", "java.lang.String");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("resolveExecutionInterceptors")),
        AwsClientInstrumentation.class.getName() + "$AwsBuilderAdvice");
  }

  public static class AwsBuilderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void methodExit(@Advice.Return final List<ExecutionInterceptor> interceptors) {
      for (ExecutionInterceptor interceptor : interceptors) {
        if (interceptor instanceof TracingExecutionInterceptor) {
          return; // list already has our interceptor, return to builder
        }
      }
      interceptors.add(
          new TracingExecutionInterceptor(
              InstrumentationContext.get(
                  "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse",
                  "java.lang.String")));
    }
  }
}
