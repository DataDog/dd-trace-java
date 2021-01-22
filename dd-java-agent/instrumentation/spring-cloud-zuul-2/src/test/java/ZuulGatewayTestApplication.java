package datadog.trace.instrumentation.springcloudzuul2.test.java;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;
import java.lang.management.ManagementFactory;
import javax.servlet.http.HttpServletRequest;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.zuul.EnableZuulProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@SpringBootApplication(scanBasePackages = "doesnotexist")
@EnableZuulProxy
public class ZuulGatewayTestApplication {

  public static void main(final String[] args) {
    SpringApplication.run(ZuulGatewayTestApplication.class, args);
    System.out.println("Started in " + ManagementFactory.getRuntimeMXBean().getUptime() + "ms");
  }

  @Bean
  public PreFilter preFilter() {
    return new PreFilter();
  }

  @Bean
  public RouteFilter routeFilter() {
    return new RouteFilter();
  }

  public class PreFilter extends ZuulFilter {

    @Override
    public String filterType() {
      return "pre";
    }

    @Override
    public int filterOrder() {
      return 0;
    }

    @Override
    public boolean shouldFilter() {
      return true;
    }

    @Override
    public Object run() {
      RequestContext ctx = RequestContext.getCurrentContext();
      HttpServletRequest request = ctx.getRequest();
      System.out.println("Request Method : " + request.getMethod() + " Request URL : " + request.getRequestURL().toString());
      return null;
    }
  }

  public class RouteFilter extends ZuulFilter {

    @Override
    public String filterType() {
      return "route";
    }

    @Override
    public int filterOrder() {
      return 0;
    }

    @Override
    public boolean shouldFilter() {
      return true;
    }

    @Override
    public Object run() {
      System.out.println("Using Route Filter");
      return null;
    }
  }
}
