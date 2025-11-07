import com.netflix.zuul.ZuulFilter
import com.netflix.zuul.context.RequestContext
import com.netflix.zuul.exception.ZuulException
import datadog.trace.agent.test.base.HttpServerTest
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.cloud.netflix.zuul.EnableZuulProxy
import org.springframework.context.annotation.Bean

import javax.servlet.http.HttpServletResponse

// Component scan defeats the purpose of configuring with specific classes
@SpringBootApplication(scanBasePackages = "doesnotexist")
@EnableZuulProxy
class AppConfig {
  @Bean
  ResponseHeaderFilter responseHeaderFilter() {
    return new ResponseHeaderFilter()
  }

  static class ResponseHeaderFilter extends ZuulFilter {
    @Override
    String filterType() {
      return "pre"
    }

    @Override
    int filterOrder() {
      return 0
    }

    @Override
    boolean shouldFilter() {
      return true
    }

    @Override
    Object run() throws ZuulException {
      RequestContext ctx = RequestContext.getCurrentContext()
      HttpServletResponse response = ctx.getResponse()
      response.addHeader(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)
      return null // ignored
    }
  }
}
