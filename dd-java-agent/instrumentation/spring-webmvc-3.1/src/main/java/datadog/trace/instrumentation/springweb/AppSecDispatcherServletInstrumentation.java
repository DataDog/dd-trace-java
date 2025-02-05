package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.appsec.api.security.model.Endpoint;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;
import net.bytebuddy.asm.Advice;
import org.springframework.context.ApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@AutoService(InstrumenterModule.class)
public class AppSecDispatcherServletInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public AppSecDispatcherServletInstrumentation() {
    super("spring-web");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.servlet.DispatcherServlet";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".RequestMappingInfoInterator"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isProtected())
            .and(named("onRefresh"))
            .and(takesArgument(0, named("org.springframework.context.ApplicationContext")))
            .and(takesArguments(1)),
        AppSecDispatcherServletInstrumentation.class.getName() + "$AppSecHandlerMappingAdvice");
  }

  public static class AppSecHandlerMappingAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterRefresh(@Advice.Argument(0) final ApplicationContext springCtx) {

      final CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      if (cbp == null) {
        return;
      }
      final Consumer<Iterator<Endpoint>> callback = cbp.getCallback(Events.get().endpoints());
      if (callback == null) {
        return;
      }
      final RequestMappingHandlerMapping handler =
          springCtx.getBean(RequestMappingHandlerMapping.class);
      if (handler == null) {
        return;
      }
      final Map<RequestMappingInfo, HandlerMethod> mappings = handler.getHandlerMethods();
      if (mappings == null || mappings.isEmpty()) {
        return;
      }
      callback.accept(new RequestMappingInfoInterator(mappings));
    }
  }
}
