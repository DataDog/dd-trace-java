package datadog.trace.instrumentation.springweb;

import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.filter.ServletRequestPathFilter;

public class OrderedServletPathRequestFilter extends DelegatingFilterProxy implements Ordered {

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  public OrderedServletPathRequestFilter() {
    super(new ServletRequestPathFilter());
  }

  public static class BeanDefinition extends AnnotatedGenericBeanDefinition {
    private static final long serialVersionUID = -5117836999188187797L;

    public BeanDefinition() {
      super(OrderedServletPathRequestFilter.class);
      // don't call setBeanClassName as it overwrites 'beanClass'
      setScope(SCOPE_SINGLETON);
      setLazyInit(true);
    }
  }
}
