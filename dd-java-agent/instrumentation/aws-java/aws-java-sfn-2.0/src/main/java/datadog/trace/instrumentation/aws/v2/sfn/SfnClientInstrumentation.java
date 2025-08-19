package datadog.trace.instrumentation.aws.v2.sfn;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

/** AWS SDK v2 Step Function instrumentation */
@AutoService(InstrumenterModule.class)
public final class SfnClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SfnClientInstrumentation() {
    super("sfn", "aws-sdk");
  }

  @Override
  public String instrumentedType() {
    return "software.amazon.awssdk.core.client.builder.SdkDefaultClientBuilder";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("resolveExecutionInterceptors")),
        SfnClientInstrumentation.class.getName() + "$AwsSfnBuilderAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".SfnInterceptor", packageName + ".InputAttributeInjector"};
  }

  public static class AwsSfnBuilderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addHandler(@Advice.Return final List<ExecutionInterceptor> interceptors) {
      for (ExecutionInterceptor interceptor : interceptors) {
        if (interceptor instanceof SfnInterceptor) {
          return;
        }
      }
      interceptors.add(new SfnInterceptor());
    }
  }
}
