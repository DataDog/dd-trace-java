import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_ROUTE_BASED_NAMING
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_PATH_NORMALIZER
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_SERVER_CONFIG_PATTERN_MATCH
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.MANUAL_INSTRUMENTATION
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR

class JakartaRsRoutePropagationTest extends InstrumentationSpecification {

  def "propagate Jakarta route through a server handler: #scenario"() {
    setup:
    injectSysConfig(HTTP_SERVER_ROUTE_BASED_NAMING, routeNaming.toString())

    when:
    runUnderTrace("netty.request") {
      activeSpan().setTag(Tags.COMPONENT, "netty")
      activeSpan().setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER)
      HTTP_RESOURCE_DECORATOR.withRoute(activeSpan(), "POST", initialRoute)
      activeSpan().setResourceName(initialResource, priority)
      runUnderTrace("vertx.route-handler") {
        activeSpan().setTag(Tags.COMPONENT, "vertx")
        if (handlerKind != null) {
          activeSpan().setTag(Tags.SPAN_KIND, handlerKind)
          activeSpan().setTag(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE)
        }
        def subResource = resource.call()
        if (subResource != null) {
          // Framework dispatch resumes after a subresource locator's span has closed.
          subResource.call()
        }
      }
    }

    then:
    assertTraces(1) {
      trace(4) {
        sortSpansByStart()
        span {
          operationName "netty.request"
          resourceName expectedResource
          parent()
          tags {
            "$Tags.COMPONENT" "netty"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.HTTP_ROUTE" expectedRoute
            withCustomIntegrationName(null)
            defaultTags()
          }
        }
        span {
          operationName "vertx.route-handler"
          resourceName "vertx.route-handler"
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "vertx"
            if (handlerKind != null) {
              "$Tags.SPAN_KIND" handlerKind
            }
            withCustomIntegrationName(null)
            defaultTags()
          }
        }
        span {
          operationName "jakarta-rs.request"
          resourceName "${resource.class.simpleName}.call"
          spanType "web"
          childOf span(1)
          tags {
            "$Tags.COMPONENT" "jakarta-rs-controller"
            defaultTags()
          }
        }
        span {
          operationName "jakarta-rs.request"
          resourceName "NestedResource.call"
          spanType "web"
          childOf span(nestedParent)
          tags {
            "$Tags.COMPONENT" "jakarta-rs-controller"
            defaultTags()
          }
        }
      }
    }

    where:
    scenario                | routeNaming | initialRoute    | initialResource      | priority                         | handlerKind           | resource                       | expectedRoute   | expectedResource
    "catch-all route"       | true        | "/"             | "POST /"             | HTTP_FRAMEWORK_ROUTE             | Tags.SPAN_KIND_SERVER | new WidgetResource()           | "/widget/{id}"  | "POST /widget/{id}"
    "path naming"           | false       | "/"             | "POST /widget/?"     | HTTP_PATH_NORMALIZER             | Tags.SPAN_KIND_SERVER | new WidgetResource()           | "/widget/{id}"  | "POST /widget/?"
    "configured resource"   | true        | "/"             | "configured"         | HTTP_SERVER_CONFIG_PATTERN_MATCH | Tags.SPAN_KIND_SERVER | new WidgetResource()           | "/widget/{id}"  | "configured"
    "manual resource"       | true        | "/"             | "manual"             | MANUAL_INSTRUMENTATION           | Tags.SPAN_KIND_SERVER | new WidgetResource()           | "/widget/{id}"  | "manual"
    "existing route"        | true        | "/mounted/{id}" | "POST /mounted/{id}" | HTTP_FRAMEWORK_ROUTE             | Tags.SPAN_KIND_SERVER | new WidgetResource()           | "/mounted/{id}" | "POST /mounted/{id}"
    "internal intermediary" | true        | "/"             | "POST /"             | HTTP_FRAMEWORK_ROUTE             | null                  | new WidgetResource()           | "/"             | "POST /"
    "nested call at root"   | true        | "/"             | "POST /"             | HTTP_FRAMEWORK_ROUTE             | Tags.SPAN_KIND_SERVER | new RootResource()             | "/"             | "POST /"
    "subresource locator"   | true        | "/"             | "POST /"             | HTTP_FRAMEWORK_ROUTE             | Tags.SPAN_KIND_SERVER | new LocatorResource()          | "/"             | "POST /"
    "returned subresource"  | true        | "/"             | "POST /"             | HTTP_FRAMEWORK_ROUTE             | Tags.SPAN_KIND_SERVER | new ReturningLocatorResource() | "/"             | "POST /"

    nestedParent = resource instanceof ReturningLocatorResource ? 1 : 2
  }

  @Path("/widget")
  static class WidgetResource {
    @POST
    @Path("/{id}")
    void call() {
      new NestedResource().call()
    }
  }

  static class RootResource {
    @POST
    @Path("/")
    void call() {
      new NestedResource().call()
    }
  }

  static class NestedResource {
    @GET
    @Path("/nested")
    void call() {
    }
  }

  static class LocatorResource {
    @Path("/locator")
    void call() {
      new NestedResource().call()
    }
  }

  @Path("/parent")
  static class ReturningLocatorResource {
    @Path("/{id}")
    NestedResource call() {
      return new NestedResource()
    }
  }
}
