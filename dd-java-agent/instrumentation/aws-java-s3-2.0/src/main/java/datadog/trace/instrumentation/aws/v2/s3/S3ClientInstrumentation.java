package datadog.trace.instrumentation.aws.v2.s3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

@AutoService(InstrumenterModule.class)
public final class S3ClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public S3ClientInstrumentation() {
    super("s3", "aws-s3");
  }

  @Override
  public String instrumentedType() {
    return "software.amazon.awssdk.core.client.builder.SdkDefaultClientBuilder";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("resolveExecutionInterceptors")),
        S3ClientInstrumentation.class.getName() + "$AwsS3BuilderAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".S3Interceptor"};
  }

  public static class AwsS3BuilderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addHandler(@Advice.Return final List<ExecutionInterceptor> interceptors) {
      for (ExecutionInterceptor interceptor : interceptors) {
        if (interceptor instanceof S3Interceptor) {
          return; // list already has our interceptor, return to builder
        }
      }
      interceptors.add(new S3Interceptor());
    }
  }
}
