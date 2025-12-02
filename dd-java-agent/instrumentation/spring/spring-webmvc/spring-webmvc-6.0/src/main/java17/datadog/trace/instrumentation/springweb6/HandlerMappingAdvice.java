package datadog.trace.instrumentation.springweb6;

import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.HandlerMapping;

/**
 * This advice creates a filter that has reference to the handlerMappings from DispatcherServlet
 * which allows the mappings to be evaluated at the beginning of the filter chain. This evaluation
 * is done inside the Servlet3Decorator.onContext method.
 */
public class HandlerMappingAdvice {

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void afterRefresh(
      @Advice.Argument(0) final ApplicationContext springCtx,
      @Advice.FieldValue("handlerMappings") final List<HandlerMapping> handlerMappings) {
    if (springCtx.containsBean("ddDispatcherFilter")) {
      final HandlerMappingResourceNameFilter filter =
          (HandlerMappingResourceNameFilter) springCtx.getBean("ddDispatcherFilter");
      boolean found = false;
      if (handlerMappings != null) {
        for (final HandlerMapping handlerMapping : handlerMappings) {
          if (handlerMapping.usesPathPatterns()) {
            found = true;
            break;
          }
        }
      }
      filter.setHasPatternMatchers(found);
    }
  }
}
