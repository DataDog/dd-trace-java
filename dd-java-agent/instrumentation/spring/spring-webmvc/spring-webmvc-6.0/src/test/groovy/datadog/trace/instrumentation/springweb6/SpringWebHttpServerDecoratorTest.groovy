package datadog.trace.instrumentation.springweb6

import datadog.trace.agent.test.InstrumentationSpecification
import org.springframework.web.servlet.ModelAndView

class SpringWebHttpServerDecoratorTest extends InstrumentationSpecification {

  def "onRender truncates long view name for tag and resource"() {
    given:
    def viewName = "a" * (SpringWebHttpServerDecorator.VIEW_NAME_TAG_LIMIT + 12)
    def modelAndView = Stub(ModelAndView) {
      getViewName() >> viewName
      getView() >> null
    }
    def span = TEST_TRACER.buildSpan("testInstrumentation", "my operation").start()

    when:
    SpringWebHttpServerDecorator.DECORATE_RENDER.onRender(span, modelAndView)
    span.finish()

    then:
    span.getTag("view.name").toString().length() == SpringWebHttpServerDecorator.VIEW_NAME_TAG_LIMIT
    span.getResourceName().length() == SpringWebHttpServerDecorator.VIEW_NAME_RESOURCE_LIMIT
  }
}
