package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.telemetry.EndpointCollector;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.context.ApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@AutoService(InstrumenterModule.class)
public class AppSecDispatcherServletWithPathPatternsInstrumentation
    extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public AppSecDispatcherServletWithPathPatternsInstrumentation() {
    super("spring-web");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.servlet.DispatcherServlet";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".RequestMappingInfoWithPathPatternsIterator"};
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed(
        "org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isProtected())
            .and(named("onRefresh"))
            .and(takesArgument(0, named("org.springframework.context.ApplicationContext")))
            .and(takesArguments(1)),
        AppSecDispatcherServletWithPathPatternsInstrumentation.class.getName()
            + "$AppSecHandlerMappingAdvice");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && InstrumenterConfig.get().isApiSecurityEndpointCollectionEnabled();
  }

  public static class AppSecHandlerMappingAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterRefresh(@Advice.Argument(0) final ApplicationContext springCtx) {
      final Map<String, RequestMappingHandlerMapping> handlers =
          springCtx.getBeansOfType(RequestMappingHandlerMapping.class);
      if (handlers == null || handlers.isEmpty()) {
        return;
      }
      final Map<RequestMappingInfo, HandlerMethod> mappings = new HashMap<>();
      for (RequestMappingHandlerMapping mapping : handlers.values()) {
        mappings.putAll(mapping.getHandlerMethods());
      }
      if (mappings.isEmpty()) {
        return;
      }
      EndpointCollector.get().supplier(new RequestMappingInfoWithPathPatternsIterator(mappings));
    }
  }
}
