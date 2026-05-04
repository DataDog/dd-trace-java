package server

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.vertx_4_0.server.RouteUpdateHelper
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.Route
import io.vertx.ext.web.RoutingContext

class RouteHandlerWrapperTest extends InstrumentationSpecification {

  void "updateRoute writes route to both spans"() {
    given:
    def context = Mock(RoutingContext)
    def request = Mock(HttpServerRequest)
    def route = Mock(Route)
    def parentSpan = Mock(AgentSpan)
    def handlerSpan = Mock(AgentSpan)

    when:
    RouteUpdateHelper.updateRouteFromMatchedRoute(context, route, parentSpan, handlerSpan)

    then:
    1 * route.getPath() >> "/items/:id"
    1 * context.mountPoint() >> null
    2 * context.request() >> request
    1 * request.path() >> "/items/123"
    1 * request.method() >> HttpMethod.GET
    1 * context.get("dd.${Tags.HTTP_ROUTE}") >> null
    1 * context.put("dd.vertx.matched_route", "/items/:id")
    1 * context.put("dd.${Tags.HTTP_ROUTE}", "/items/:id")
    1 * parentSpan.setTag(Tags.HTTP_ROUTE, "/items/:id")
    1 * parentSpan.setResourceName(_, ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE)
    1 * handlerSpan.getSpanName() >> "vertx.route-handler"
    1 * handlerSpan.getResourceNamePriority() >> Byte.MIN_VALUE
    1 * handlerSpan.setTag(Tags.HTTP_ROUTE, "/items/:id")
    1 * handlerSpan.setResourceName(_, ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE)
    0 * _
  }

  void "updateRoute does not write route to non vertx handler span"() {
    given:
    def context = Mock(RoutingContext)
    def request = Mock(HttpServerRequest)
    def route = Mock(Route)
    def parentSpan = Mock(AgentSpan)
    def handlerSpan = Mock(AgentSpan)

    when:
    RouteUpdateHelper.updateRouteFromMatchedRoute(context, route, parentSpan, handlerSpan)

    then:
    1 * route.getPath() >> "/items/:id"
    1 * context.mountPoint() >> null
    2 * context.request() >> request
    1 * request.path() >> "/items/123"
    1 * request.method() >> HttpMethod.GET
    1 * context.get("dd.${Tags.HTTP_ROUTE}") >> null
    1 * context.put("dd.vertx.matched_route", "/items/:id")
    1 * context.put("dd.${Tags.HTTP_ROUTE}", "/items/:id")
    1 * parentSpan.setTag(Tags.HTTP_ROUTE, "/items/:id")
    1 * parentSpan.setResourceName(_, ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE)
    1 * handlerSpan.getSpanName() >> "some.other.span"
    0 * handlerSpan.setTag(_, _)
    0 * _
  }

  void "updateRoute does not replace root route when one exists"() {
    given:
    def context = Mock(RoutingContext)
    def request = Mock(HttpServerRequest)
    def route = Mock(Route)
    def parentSpan = Mock(AgentSpan)
    def handlerSpan = Mock(AgentSpan)

    when:
    RouteUpdateHelper.updateRouteFromMatchedRoute(context, route, parentSpan, handlerSpan)

    then:
    1 * route.getPath() >> "/"
    1 * context.mountPoint() >> null
    2 * context.request() >> request
    1 * request.path() >> "/"
    1 * request.method() >> HttpMethod.GET
    1 * context.get("dd.${Tags.HTTP_ROUTE}") >> null
    1 * context.put("dd.vertx.matched_route", "/")
    1 * parentSpan.getTag(Tags.HTTP_ROUTE) >> "/existing"
    0 * context.put(_, _)
    0 * parentSpan.setTag(_, _)
    0 * handlerSpan.setTag(_, _)
  }
}
