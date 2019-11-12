package datadog.trace.instrumentation.okhttp3;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

import java.util.Map;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

@AutoService(Instrumenter.class)
public class OkHttp3Instrumentation extends Instrumenter.Default {

  public OkHttp3Instrumentation() {
    super("okhttp", "okhttp-3");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("okhttp3.OkHttpClient");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
      "datadog.trace.agent.decorator.BaseDecorator",
      "datadog.trace.agent.decorator.ClientDecorator",
      "datadog.trace.agent.decorator.HttpClientDecorator",
      packageName + ".OkHttpClientDecorator",
      packageName + ".RequestBuilderInjectAdapter",
      packageName + ".TagWrapper",
      packageName + ".TracingInterceptor",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
      isConstructor().and(takesArgument(0, named("okhttp3.OkHttpClient$Builder"))),
      OkHttp3Advice.class.getName());
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
