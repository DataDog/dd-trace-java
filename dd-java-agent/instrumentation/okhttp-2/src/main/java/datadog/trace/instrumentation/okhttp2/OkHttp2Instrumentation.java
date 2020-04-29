package datadog.trace.instrumentation.okhttp2;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.OkHttpClient;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class OkHttp2Instrumentation extends Instrumenter.Default {
  public OkHttp2Instrumentation() {
    super("okhttp", "okhttp-2");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("com.squareup.okhttp.OkHttpClient");
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
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
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
    }
  }
}
