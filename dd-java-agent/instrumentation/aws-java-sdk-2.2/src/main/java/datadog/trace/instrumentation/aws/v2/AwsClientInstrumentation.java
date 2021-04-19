package datadog.trace.instrumentation.aws.v2;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

/** AWS SDK v2 instrumentation */
@AutoService(Instrumenter.class)
public final class AwsClientInstrumentation extends AbstractAwsClientInstrumentation {

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassNamed("software.amazon.awssdk.core.client.builder.SdkDefaultClientBuilder");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("software.amazon.awssdk.core.client.builder.SdkDefaultClientBuilder");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
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
      interceptors.add(new TracingExecutionInterceptor());
    }
  }
}
