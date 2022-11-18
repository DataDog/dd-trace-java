package datadog.trace.instrumentation.springweb;

import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ServletRequestPathFilter;

public class OrderedServletPathRequestFilter extends ServletRequestPathFilter implements Ordered {
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  public static class BeanDefinition extends AnnotatedGenericBeanDefinition {
    private static final long serialVersionUID = -5117836999188187797L;

    public BeanDefinition() {
      super(OrderedServletPathRequestFilter.class);
      setBeanClassName(OrderedServletPathRequestFilter.class.getName());
      setScope(SCOPE_SINGLETON);
      setLazyInit(true);
    }
  }
}
