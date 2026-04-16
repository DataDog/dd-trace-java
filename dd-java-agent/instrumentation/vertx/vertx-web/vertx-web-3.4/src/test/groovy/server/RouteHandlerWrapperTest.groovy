package server

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.vertx_3_4.server.RouteUpdateHelper
import io.vertx.ext.web.RoutingContext

class RouteHandlerWrapperTest extends InstrumentationSpecification {

  void "updateRoute writes route to both spans"() {
    given:
    def context = Mock(RoutingContext)
    def parentSpan = Mock(AgentSpan)
    def handlerSpan = Mock(AgentSpan)

    when:
    RouteUpdateHelper.updateRoute(
      context, "GET", "/items/:id", parentSpan, handlerSpan, "matches")

    then:
    1 * context.get("dd.${Tags.HTTP_ROUTE}") >> null
    1 * context.put("dd.${Tags.HTTP_ROUTE}", "/items/:id")
    1 * parentSpan.setTag(Tags.HTTP_ROUTE, "/items/:id")
    1 * parentSpan.setTag("dd.debug.vertx.route_overwrite", "matches:->/items/:id")
    1 * handlerSpan.getResourceNamePriority() >> Byte.MIN_VALUE
    1 * handlerSpan.setTag(Tags.HTTP_ROUTE, "/items/:id")
    1 * handlerSpan.setTag("dd.debug.vertx.route_overwrite", "matches:->/items/:id")
    0 * _
  }

  void "updateRoute does not replace root route when one exists"() {
    given:
    def context = Mock(RoutingContext)
    def parentSpan = Mock(AgentSpan)
    def handlerSpan = Mock(AgentSpan)

    when:
    RouteUpdateHelper.updateRoute(context, "GET", "/", parentSpan, handlerSpan, "matches")

    then:
    1 * context.get("dd.${Tags.HTTP_ROUTE}") >> null
    1 * parentSpan.getTag(Tags.HTTP_ROUTE) >> "/existing"
    1 * handlerSpan.getTag(Tags.HTTP_ROUTE) >> "/existing"
    0 * context.put(_, _)
    0 * parentSpan.setTag(_, _)
    0 * handlerSpan.setTag(_, _)
  }
}
