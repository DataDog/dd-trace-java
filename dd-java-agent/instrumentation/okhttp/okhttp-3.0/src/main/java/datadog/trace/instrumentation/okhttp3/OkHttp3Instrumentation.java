package datadog.trace.instrumentation.okhttp3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ActiveSubsystems;
import net.bytebuddy.asm.Advice;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

@AutoService(InstrumenterModule.class)
public class OkHttp3Instrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

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
      packageName + ".AppSecInterceptor",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
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
      if (ActiveSubsystems.APPSEC_ACTIVE) {
        builder.addInterceptor(new AppSecInterceptor());
      }
    }
  }
}
