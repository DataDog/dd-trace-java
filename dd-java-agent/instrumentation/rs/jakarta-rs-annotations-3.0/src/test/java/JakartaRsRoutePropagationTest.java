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
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JakartaRsRoutePropagationTest extends AbstractInstrumentationTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("propagateRouteThroughServerHandlerArguments")
  void propagateRouteThroughServerHandler(
      String scenario,
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

  static Stream<Arguments> propagateRouteThroughServerHandlerArguments() {
    return Stream.of(
        arguments(
            "catch-all route",
            true,
            "/",
            "POST /",
            HTTP_FRAMEWORK_ROUTE,
            Tags.SPAN_KIND_SERVER,
            new WidgetResource(),
            "/widget/{id}",
            "POST /widget/{id}"),
        arguments(
            "path naming",
            false,
            "/",
            "POST /widget/?",
            HTTP_PATH_NORMALIZER,
            Tags.SPAN_KIND_SERVER,
            new WidgetResource(),
            "/widget/{id}",
            "POST /widget/?"),
        arguments(
            "configured resource",
            true,
            "/",
            "configured",
            HTTP_SERVER_CONFIG_PATTERN_MATCH,
            Tags.SPAN_KIND_SERVER,
            new WidgetResource(),
            "/widget/{id}",
            "configured"),
        arguments(
            "manual resource",
            true,
            "/",
            "manual",
            MANUAL_INSTRUMENTATION,
            Tags.SPAN_KIND_SERVER,
            new WidgetResource(),
            "/widget/{id}",
            "manual"),
        arguments(
            "existing route",
            true,
            "/mounted/{id}",
            "POST /mounted/{id}",
            HTTP_FRAMEWORK_ROUTE,
            Tags.SPAN_KIND_SERVER,
            new WidgetResource(),
            "/mounted/{id}",
            "POST /mounted/{id}"),
        arguments(
            "internal intermediary",
            true,
            "/",
            "POST /",
            HTTP_FRAMEWORK_ROUTE,
            null,
            new WidgetResource(),
            "/",
            "POST /"),
        arguments(
            "nested call at root",
            true,
            "/",
            "POST /",
            HTTP_FRAMEWORK_ROUTE,
            Tags.SPAN_KIND_SERVER,
            new RootResource(),
            "/",
            "POST /"),
        arguments(
            "subresource locator",
            true,
            "/",
            "POST /",
            HTTP_FRAMEWORK_ROUTE,
            Tags.SPAN_KIND_SERVER,
            new LocatorResource(),
            "/",
            "POST /"),
        arguments(
            "returned subresource",
            true,
            "/",
            "POST /",
            HTTP_FRAMEWORK_ROUTE,
            Tags.SPAN_KIND_SERVER,
            new ReturningLocatorResource(),
            "/",
            "POST /"));
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
