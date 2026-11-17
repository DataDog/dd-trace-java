import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_ROUTE_BASED_NAMING;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_PATH_NORMALIZER;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.HTTP_SERVER_CONFIG_PATTERN_MATCH;
import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.MANUAL_INSTRUMENTATION;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.isNull;
import static datadog.trace.test.junit.utils.assertions.Matchers.matches;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;

public class JakartaRsRoutePropagationTest extends AbstractInstrumentationTest {

  @TableTest({
    "Scenario              | Route naming | Initial route | Initial resource   | Priority                         | Handler kind | Resource                 | Expected route | Expected resource ",
    "catch-all route       | true         | /             | POST /             | HTTP_FRAMEWORK_ROUTE             | server       | WidgetResource           | /widget/{id}   | POST /widget/{id} ",
    "path naming           | false        | /             | POST /widget/?     | HTTP_PATH_NORMALIZER             | server       | WidgetResource           | /widget/{id}   | POST /widget/?    ",
    "configured resource   | true         | /             | configured         | HTTP_SERVER_CONFIG_PATTERN_MATCH | server       | WidgetResource           | /widget/{id}   | configured        ",
    "manual resource       | true         | /             | manual             | MANUAL_INSTRUMENTATION           | server       | WidgetResource           | /widget/{id}   | manual            ",
    "existing route        | true         | /mounted/{id} | POST /mounted/{id} | HTTP_FRAMEWORK_ROUTE             | server       | WidgetResource           | /mounted/{id}  | POST /mounted/{id}",
    "internal intermediary | true         | /             | POST /             | HTTP_FRAMEWORK_ROUTE             |              | WidgetResource           | /              | POST /            ",
    "nested call at root   | true         | /             | POST /             | HTTP_FRAMEWORK_ROUTE             | server       | RootResource             | /              | POST /            ",
    "subresource locator   | true         | /             | POST /             | HTTP_FRAMEWORK_ROUTE             | server       | LocatorResource          | /              | POST /            ",
    "returned subresource  | true         | /             | POST /             | HTTP_FRAMEWORK_ROUTE             | server       | ReturningLocatorResource | /              | POST /            "
  })
  void propagateRouteThroughServerHandler(
      boolean routeNaming,
      String initialRoute,
      String initialResource,
      byte priority,
      String handlerKind,
      Resource resource,
      String expectedRoute,
      String expectedResource) {
    injectSysConfig(HTTP_SERVER_ROUTE_BASED_NAMING, Boolean.toString(routeNaming));

    runUnderTrace(
        "netty.request",
        () -> {
          activeSpan().setTag(Tags.COMPONENT, "netty");
          activeSpan().setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
          HTTP_RESOURCE_DECORATOR.withRoute(activeSpan(), "POST", initialRoute);
          activeSpan().setResourceName(initialResource, priority);
          return runUnderTrace(
              "vertx.route-handler",
              () -> {
                activeSpan().setTag(Tags.COMPONENT, "vertx");
                if (handlerKind != null) {
                  activeSpan().setTag(Tags.SPAN_KIND, handlerKind);
                  activeSpan().setTag(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
                }
                NestedResource subResource = resource.call();
                if (subResource != null) {
                  // Framework dispatch resumes after a subresource locator's span has closed.
                  subResource.call();
                }
                return null;
              });
        });

    int nestedParent = resource instanceof ReturningLocatorResource ? 1 : 2;
    String resourceName = resource.getClass().getSimpleName() + ".call";
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span()
                .operationName("netty.request")
                .resourceName(expectedResource::contentEquals)
                .root()
                .error(false)
                .tags(
                    defaultTags(),
                    tag(Tags.COMPONENT, is("netty")),
                    tag(Tags.SPAN_KIND, is(Tags.SPAN_KIND_SERVER)),
                    tag(Tags.HTTP_ROUTE, is(expectedRoute))),
            span()
                .operationName("vertx.route-handler")
                .resourceName("vertx.route-handler")
                .childOfIndex(0)
                .error(false)
                .tags(
                    defaultTags(),
                    tag(Tags.COMPONENT, is("vertx")),
                    tag(Tags.SPAN_KIND, handlerKind == null ? isNull() : is(handlerKind))),
            span()
                .operationName("jakarta-rs.request")
                .resourceName(resourceName::contentEquals)
                .type("web")
                .childOfIndex(1)
                .error(false)
                .tags(defaultTags(), tag(Tags.COMPONENT, matches("jakarta-rs-controller"))),
            span()
                .operationName("jakarta-rs.request")
                .resourceName("NestedResource.call"::contentEquals)
                .type("web")
                .childOfIndex(nestedParent)
                .error(false)
                .tags(defaultTags(), tag(Tags.COMPONENT, matches("jakarta-rs-controller")))));
  }

  @TypeConverter
  public static byte toPriority(String value) {
    switch (value) {
      case "HTTP_FRAMEWORK_ROUTE":
        return HTTP_FRAMEWORK_ROUTE;
      case "HTTP_PATH_NORMALIZER":
        return HTTP_PATH_NORMALIZER;
      case "HTTP_SERVER_CONFIG_PATTERN_MATCH":
        return HTTP_SERVER_CONFIG_PATTERN_MATCH;
      case "MANUAL_INSTRUMENTATION":
        return MANUAL_INSTRUMENTATION;
      default:
        throw new IllegalArgumentException("Unknown priority: " + value);
    }
  }

  @TypeConverter
  public static Resource toResource(String value) {
    switch (value) {
      case "WidgetResource":
        return new WidgetResource();
      case "RootResource":
        return new RootResource();
      case "LocatorResource":
        return new LocatorResource();
      case "ReturningLocatorResource":
        return new ReturningLocatorResource();
      default:
        throw new IllegalArgumentException("Unknown resource: " + value);
    }
  }

  abstract static class Resource {
    public abstract NestedResource call();
  }

  @Path("/widget")
  static class WidgetResource extends Resource {
    @Override
    @POST
    @Path("/{id}")
    public NestedResource call() {
      new NestedResource().call();
      return null;
    }
  }

  static class RootResource extends Resource {
    @Override
    @POST
    @Path("/")
    public NestedResource call() {
      new NestedResource().call();
      return null;
    }
  }

  static class NestedResource {
    @GET
    @Path("/nested")
    public void call() {}
  }

  static class LocatorResource extends Resource {
    @Override
    @Path("/locator")
    public NestedResource call() {
      new NestedResource().call();
      return null;
    }
  }

  @Path("/parent")
  static class ReturningLocatorResource extends Resource {
    @Override
    @Path("/{id}")
    public NestedResource call() {
      return new NestedResource();
    }
  }
}
