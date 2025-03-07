package datadog.trace.instrumentation.aws.v2.dynamodb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

@AutoService(InstrumenterModule.class)
public final class DynamoDbClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public DynamoDbClientInstrumentation() {
    super("dynamodb", "aws-dynamodb");
  }

  @Override
  public String instrumentedType() {
    return "software.amazon.awssdk.core.client.builder.SdkDefaultClientBuilder";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("resolveExecutionInterceptors")),
        DynamoDbClientInstrumentation.class.getName() + "$AwsDynamoDbBuilderAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".DynamoDbInterceptor", packageName + ".DynamoDbUtil"};
  }

  public static class AwsDynamoDbBuilderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addHandler(@Advice.Return final List<ExecutionInterceptor> interceptors) {
      for (ExecutionInterceptor interceptor : interceptors) {
        if (interceptor instanceof DynamoDbInterceptor) {
          return; // list already has our interceptor, return to builder
        }
      }
      interceptors.add(new DynamoDbInterceptor());
    }
  }
}
