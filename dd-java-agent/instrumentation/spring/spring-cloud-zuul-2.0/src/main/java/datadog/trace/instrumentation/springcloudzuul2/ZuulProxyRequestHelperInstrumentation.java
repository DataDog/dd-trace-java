package datadog.trace.instrumentation.springcloudzuul2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.springcloudzuul2.HeaderUtils.DD_PACKAGE_PREFIX;
import static datadog.trace.instrumentation.springcloudzuul2.HeaderUtils.EXCLUDED_HEADERS;
import static datadog.trace.instrumentation.springcloudzuul2.HeaderUtils.HAYSTACK_PACKAGE_PREFIX;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Locale;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class ZuulProxyRequestHelperInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ZuulProxyRequestHelperInstrumentation() {
    super("spring-cloud-zuul");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.cloud.netflix.zuul.filters.ProxyRequestHelper";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("isIncludedHeader")).and(takesArgument(0, String.class)),
        ZuulProxyRequestHelperInstrumentation.class.getName() + "$ProxyRequestHelperAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".HeaderUtils"};
  }

  /**
   * The purpose of this instrumentation is to prevent the overwriting of instrumented headers when
   * a request is passed through a Zuul proxy. This instrumentation will ensure that the proxy's
   * action of filtering/forwarding the incoming request will not overwrite the headers added by the
   * agent when instrumenting the proxy
   */
  public static class ProxyRequestHelperAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) final String header, @Advice.Return(readOnly = false) boolean include) {
      if (!include) return;

      String lowercaseHeader = header.toLowerCase(Locale.ROOT);
      if (lowercaseHeader.startsWith(HAYSTACK_PACKAGE_PREFIX)
          || lowercaseHeader.startsWith(DD_PACKAGE_PREFIX)
          || EXCLUDED_HEADERS.contains(lowercaseHeader)) {
        include = false;
      }
    }
  }
}
