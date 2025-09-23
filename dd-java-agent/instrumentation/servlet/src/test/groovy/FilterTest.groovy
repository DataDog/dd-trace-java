import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.servlet.filter.FilterDecorator

import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.FilterConfig
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class FilterTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.servlet-filter.enabled", "true")
  }

  def "test doFilter no-parent"() {
    when:
    filter.doFilter(null, null, null)

    then:
    assertTraces(0) {}

    where:
    filter = new TestFilter()
  }

  def "test doFilter with parent"() {
    when:
    runUnderTrace("parent") {
      filter.doFilter(null, null, null)
    }

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          operationName "servlet.filter"
          resourceName "${consistentClassName(filter.class)}.doFilter"
          childOf span(0)
          tags {
            "component" "java-web-servlet-filter"
            defaultTags()
          }
        }
      }
    }

    where:
    filter << [new TestFilter(), new TestFilter() {}]
  }

  def "test doFilter exception"() {
    setup:
    def ex = new Exception("some error")
    def filter = new TestFilter() {
        @Override
        void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) {
          throw ex
        }
      }

    when:
    runUnderTrace("parent") {
      filter.doFilter(null, null, null)
    }

    then:
    def th = thrown(Exception)
    th == ex

    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent", null, ex)
        span {
          operationName "servlet.filter"
          resourceName "${consistentClassName(filter.class)}.doFilter"
          childOf span(0)
          errored true
          tags {
            "component" "java-web-servlet-filter"
            defaultTags()
            errorTags(ex.class, ex.message)
          }
        }
      }
    }
  }

  static class TestFilter implements Filter {

    @Override
    void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
    }

    @Override
    void destroy() {
    }
  }

  def consistentClassName(Class klass) {
    return FilterDecorator.DECORATE.className(klass)
  }
}
