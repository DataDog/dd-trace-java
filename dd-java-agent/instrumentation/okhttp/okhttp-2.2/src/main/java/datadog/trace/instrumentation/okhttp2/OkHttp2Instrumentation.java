package datadog.trace.instrumentation.okhttp2;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.OkHttpClient;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ActiveSubsystems;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class OkHttp2Instrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public OkHttp2Instrumentation() {
    super("okhttp", "okhttp-2");
  }

  @Override
  public String instrumentedType() {
    return "com.squareup.okhttp.OkHttpClient";
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
        isConstructor(), OkHttp2Instrumentation.class.getName() + "$OkHttp2ClientAdvice");
  }

  public static class OkHttp2ClientAdvice {
    @Advice.OnMethodExit
    public static void addTracingInterceptor(@Advice.This final OkHttpClient client) {
      for (final Interceptor interceptor : client.interceptors()) {
        if (interceptor instanceof TracingInterceptor) {
          return;
        }
      }
      client.interceptors().add(new TracingInterceptor());
      if (ActiveSubsystems.APPSEC_ACTIVE) {
        client.interceptors().add(new AppSecInterceptor());
      }
    }
  }
}
