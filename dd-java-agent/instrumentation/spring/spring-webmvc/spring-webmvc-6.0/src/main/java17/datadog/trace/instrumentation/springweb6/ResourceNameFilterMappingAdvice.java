package datadog.trace.instrumentation.springweb6;

import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Wires the {@code DispatcherServlet} handler mappings into the (opt-in) {@link
 * HandlerMappingResourceNameFilter} after the context refreshes, so the filter can evaluate them at
 * the start of the filter chain. Only active when the {@code spring-path-filter} integration is
 * enabled (see {@code ResourceNameFilterMappingInstrumentation}).
 */
public class ResourceNameFilterMappingAdvice {

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void afterRefresh(
      @Advice.Argument(0) final ApplicationContext springCtx,
      @Advice.FieldValue("handlerMappings") final List<HandlerMapping> handlerMappings) {
    if (handlerMappings != null && springCtx.containsBean("ddDispatcherFilter")) {
      final HandlerMappingResourceNameFilter filter =
          (HandlerMappingResourceNameFilter) springCtx.getBean("ddDispatcherFilter");
      filter.setHandlerMappings(handlerMappings);
    }
  }
}
