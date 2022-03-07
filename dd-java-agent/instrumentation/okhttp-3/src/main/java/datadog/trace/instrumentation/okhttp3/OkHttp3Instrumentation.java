package datadog.trace.instrumentation.okhttp3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

@AutoService(Instrumenter.class)
public class OkHttp3Instrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public OkHttp3Instrumentation() {
    super("okhttp", "okhttp-3");
  }

  @Override
  public String instrumentedType() {
    return "okhttp3.OkHttpClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".RequestBuilderInjectAdapter",
      packageName + ".OkHttpClientDecorator",
      packageName + ".TracingInterceptor",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor().and(takesArgument(0, named("okhttp3.OkHttpClient$Builder"))),
        OkHttp3Instrumentation.class.getName() + "$OkHttp3Advice");
  }

  public static class OkHttp3Advice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void addTracingInterceptor(
        @Advice.Argument(0) final OkHttpClient.Builder builder) {
      for (final Interceptor interceptor : builder.interceptors()) {
        if (interceptor instanceof TracingInterceptor) {
          return;
        }
      }
      final TracingInterceptor interceptor = new TracingInterceptor();
      builder.addInterceptor(interceptor);
    }
  }
}
