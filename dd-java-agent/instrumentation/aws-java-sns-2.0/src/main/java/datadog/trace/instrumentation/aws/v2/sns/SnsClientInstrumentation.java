package datadog.trace.instrumentation.aws.v2.sns;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

/** AWS SDK v2 SNS instrumentation */
@AutoService(InstrumenterModule.class)
public final class SnsClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {
  private static final String INSTRUMENTATION_NAME = "aws-sdk";

  public SnsClientInstrumentation() {
    super(INSTRUMENTATION_NAME);
  }

  @Override
  public String instrumentedType() {
    return "software.amazon.awssdk.core.client.builder.SdkDefaultClientBuilder";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("resolveExecutionInterceptors")),
        SnsClientInstrumentation.class.getName() + "$AwsSnsBuilderAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SnsInterceptor", packageName + ".MessageAttributeInjector"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.amazonaws.AmazonWebServiceRequest",
        "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
  }

  public static class AwsSnsBuilderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addHandler(@Advice.Return final List<ExecutionInterceptor> interceptors) {
      for (ExecutionInterceptor interceptor : interceptors) {
        if (interceptor instanceof SnsInterceptor) {
          return; // list already has our interceptor, return to builder
        }
      }
      interceptors.add(new SnsInterceptor());
      //          InstrumentationContext.get(
      //              "software.amazon.awssdk.core.SdkRequest",
      //              "datadog.trace.bootstrap.instrumentation.api.AgentSpan")));
    }
  }
}
