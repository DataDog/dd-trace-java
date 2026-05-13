package opentelemetry14;

import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.DDTags.DD_SVC_SRC;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_STATUS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static io.opentelemetry.api.trace.SpanKind.CLIENT;
import static io.opentelemetry.api.trace.SpanKind.CONSUMER;
import static io.opentelemetry.api.trace.SpanKind.INTERNAL;
import static io.opentelemetry.api.trace.SpanKind.PRODUCER;
import static io.opentelemetry.api.trace.SpanKind.SERVER;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.opentelemetry.shim.trace.OtelConventions;
import datadog.trace.agent.test.assertions.Matcher;
import datadog.trace.agent.test.assertions.Matchers;
import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.bootstrap.instrumentation.api.ServiceNameSources;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class OpenTelemetry14ConventionsTest extends AbstractOpenTelemetry14Test {
  private static final String SPAN_KIND_INTERNAL = "internal";
  private static final String OPERATION_NAME_SPECIFIC_ATTRIBUTE = "operation.name";

  static Stream<Arguments> testSpanNameConventionsArguments() {
    return Stream.of(
        // Fallback behavior
        arguments(null, emptyMap(), "internal"),
        // Internal spans
        arguments(INTERNAL, emptyMap(), "internal"),
        // Server spans
        arguments(SERVER, emptyMap(), "server.request"),
        arguments(SERVER, attributes("http.request.method", "GET"), "http.server.request"),
        arguments(SERVER, attributes("http.request.method", "GET"), "http.server.request"),
        arguments(SERVER, attributes("network.protocol.name", "amqp"), "amqp.server.request"),
        // Client spans
        arguments(CLIENT, emptyMap(), "client.request"),
        arguments(CLIENT, attributes("http.request.method", "GET"), "http.client.request"),
        arguments(CLIENT, attributes("db.system", "mysql"), "mysql.query"),
        arguments(CLIENT, attributes("network.protocol.name", "amqp"), "amqp.client.request"),
        arguments(CLIENT, attributes("network.protocol.name", "AMQP"), "amqp.client.request"),
        // Messaging spans
        arguments(PRODUCER, emptyMap(), "producer"),
        arguments(CONSUMER, emptyMap(), "consumer"),
        arguments(
            CONSUMER,
            attributes("messaging.system", "rabbitmq", "messaging.operation", "publish"),
            "rabbitmq.publish"),
        arguments(
            PRODUCER,
            attributes("messaging.system", "rabbitmq", "messaging.operation", "publish"),
            "rabbitmq.publish"),
        arguments(
            CLIENT,
            attributes("messaging.system", "rabbitmq", "messaging.operation", "publish"),
            "rabbitmq.publish"),
        arguments(
            SERVER,
            attributes("messaging.system", "rabbitmq", "messaging.operation", "publish"),
            "rabbitmq.publish"),
        // RPC spans
        arguments(CLIENT, attributes("rpc.system", "grpc"), "grpc.client.request"),
        arguments(SERVER, attributes("rpc.system", "grpc"), "grpc.server.request"),
        arguments(CLIENT, attributes("rpc.system", "aws-api"), "aws.client.request"),
        arguments(
            CLIENT,
            attributes("rpc.system", "aws-api", "rpc.service", "helloworld"),
            "aws.helloworld.request"),
        arguments(SERVER, attributes("rpc.system", "aws-api"), "aws-api.server.request"),
        // FAAS spans
        arguments(
            CLIENT,
            attributes(
                "faas.invoked_provider", "alibaba_cloud", "faas.invoked_name", "my-function"),
            "alibaba_cloud.my-function.invoke"),
        arguments(SERVER, attributes("faas.trigger", "datasource"), "datasource.invoke"),
        // GraphQL spans
        arguments(
            INTERNAL, attributes("graphql.operation.type", "query"), "graphql.server.request"),
        arguments(null, attributes("graphql.operation.type", "query"), "graphql.server.request"),
        // User override
        arguments(
            CLIENT, attributes("db.system", "mysql", "operation.name", "db.query"), "db.query"),
        arguments(
            CLIENT, attributes("db.system", "mysql", "operation.name", "DB.query"), "db.query"));
  }

  @ParameterizedTest(name = "[{index}] {0} {1} -> {2}")
  @MethodSource("testSpanNameConventionsArguments")
  void testSpanNameConventions(
      SpanKind kind, Map<String, String> attributes, String expectedOperationName) {
    SpanBuilder builder = this.otelTracer.spanBuilder("some-name");
    if (kind != null) {
      builder.setSpanKind(kind);
    }
    attributes.forEach(builder::setAttribute);
    builder.startSpan().end();

    String expectedSpanKindTag = OtelConventions.toSpanKindTagValue(kind == null ? INTERNAL : kind);
    List<TagsMatcher> tagMatchers = new ArrayList<>();
    tagMatchers.add(defaultTags());
    tagMatchers.add(tag(SPAN_KIND, is(expectedSpanKindTag)));
    attributes.forEach(
        (key, value) -> {
          if (!OPERATION_NAME_SPECIFIC_ATTRIBUTE.equals(key)) {
            tagMatchers.add(tag(key, is(value)));
          }
        });

    assertTraces(
        trace(
            span()
                .root()
                .operationName(expectedOperationName)
                .resourceName("some-name")
                .tags(tagMatchers.toArray(new TagsMatcher[0]))));
  }

  static Stream<Arguments> testSpanSpecificTagsArguments() {
    return Stream.of(
        arguments(true, true),
        arguments(true, false),
        arguments(false, true),
        arguments(false, false));
  }

  @ParameterizedTest(name = "[{index}] setInBuilder={0} useAttributeKey={1}")
  @MethodSource("testSpanSpecificTagsArguments")
  void testSpanSpecificTags(boolean setInBuilder, boolean useAttributeKey) {
    SpanBuilder builder = this.otelTracer.spanBuilder("some-name");

    if (setInBuilder) {
      if (useAttributeKey) {
        builder
            .setAttribute(stringKey("operation.name"), "my-operation")
            .setAttribute(stringKey("service.name"), "my-service")
            .setAttribute(stringKey("resource.name"), "/my-resource")
            .setAttribute(stringKey("span.type"), "http");
      } else {
        builder
            .setAttribute("operation.name", "my-operation")
            .setAttribute("service.name", "my-service")
            .setAttribute("resource.name", "/my-resource")
            .setAttribute("span.type", "http");
      }
    }
    Span result = builder.startSpan();
    if (!setInBuilder) {
      if (useAttributeKey) {
        result
            .setAttribute(stringKey("operation.name"), "my-operation")
            .setAttribute(stringKey("service.name"), "my-service")
            .setAttribute(stringKey("resource.name"), "/my-resource")
            .setAttribute(stringKey("span.type"), "http");
      } else {
        result
            .setAttribute("operation.name", "my-operation")
            .setAttribute("service.name", "my-service")
            .setAttribute("resource.name", "/my-resource")
            .setAttribute("span.type", "http");
      }
    }
    result.end();

    assertTraces(
        trace(
            span()
                .root()
                .operationName("my-operation")
                .resourceName("/my-resource")
                .serviceName("my-service")
                .type("http")
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)),
                    tag(DD_SVC_SRC, isManuallySet()))));
  }

  static Stream<Arguments> testSpanAnalyticsEventSpecificTagArguments() {
    return Stream.of(
        arguments(true, true, 1),
        arguments(true, Boolean.TRUE, 1),
        arguments(true, false, 0),
        arguments(true, Boolean.FALSE, 0),
        arguments(true, null, 0),
        arguments(true, "true", 1),
        arguments(true, "false", 0),
        arguments(true, "TRUE", 1),
        arguments(true, "something-else", 0),
        arguments(true, "", 0),
        arguments(false, true, 1),
        arguments(false, Boolean.TRUE, 1),
        arguments(false, false, 0),
        arguments(false, Boolean.FALSE, 0),
        arguments(false, null, 0),
        arguments(false, "true", 1),
        arguments(false, "false", 0),
        arguments(false, "TRUE", 1),
        arguments(false, "something-else", 0),
        arguments(false, "", 0));
  }

  @ParameterizedTest(name = "[{index}] setInBuilder={0} value={1}")
  @MethodSource("testSpanAnalyticsEventSpecificTagArguments")
  void testSpanAnalyticsEventSpecificTag(boolean setInBuilder, Object value, int expectedMetric) {
    SpanBuilder builder = this.otelTracer.spanBuilder("some-name");

    if (setInBuilder) {
      if (value instanceof Boolean) {
        builder.setAttribute("analytics.event", (boolean) value);
      } else if (value instanceof String) {
        builder.setAttribute("analytics.event", (String) value);
      }
      // null case: don't set anything
    }
    Span result = builder.startSpan();
    if (!setInBuilder) {
      if (value instanceof Boolean) {
        result.setAttribute("analytics.event", (boolean) value);
      } else if (value instanceof String) {
        result.setAttribute("analytics.event", (String) value);
      }
      // null case: don't set anything
    }
    result.end();

    List<TagsMatcher> tagMatchers = new ArrayList<>();
    tagMatchers.add(defaultTags());
    tagMatchers.add(tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)));
    if (value != null) {
      tagMatchers.add(tag(ANALYTICS_SAMPLE_RATE, is(expectedMetric)));
    }
    assertTraces(
        trace(
            span().root().operationName("internal").tags(tagMatchers.toArray(new TagsMatcher[0]))));
  }

  static Stream<Arguments> testSpanHttpResponseStatusCodeSpecificTagArguments() {
    return Stream.of(
        arguments(true, false, null, 0),
        arguments(true, false, 200, 200),
        arguments(true, false, 404L, 404),
        arguments(true, false, (long) 500, 500),
        arguments(false, false, null, 0),
        arguments(false, false, 200, 200),
        arguments(false, false, 404L, 404),
        arguments(false, false, (long) 500, 500),
        arguments(true, true, null, 0),
        arguments(true, true, 200, 200),
        arguments(true, true, 404L, 404),
        arguments(true, true, (long) 500, 500),
        arguments(false, true, null, 0),
        arguments(false, true, 200, 200),
        arguments(false, true, 404L, 404),
        arguments(false, true, (long) 500, 500));
  }

  @ParameterizedTest(name = "[{index}] setInBuilder={0} attributeKey={1} value={2}")
  @MethodSource("testSpanHttpResponseStatusCodeSpecificTagArguments")
  void testSpanHttpResponseStatusCodeSpecificTag(
      boolean setInBuilder, boolean attributeKey, Object value, int expectedStatus) {
    SpanBuilder builder = this.otelTracer.spanBuilder("some-name");

    if (setInBuilder) {
      if (value != null) {
        if (attributeKey) {
          builder.setAttribute(longKey("http.response.status_code"), ((Number) value).longValue());
        } else {
          builder.setAttribute("http.response.status_code", ((Number) value).longValue());
        }
      }
    }
    Span result = builder.startSpan();
    if (!setInBuilder) {
      if (value != null) {
        if (attributeKey) {
          result.setAttribute(longKey("http.response.status_code"), ((Number) value).longValue());
        } else {
          result.setAttribute("http.response.status_code", ((Number) value).longValue());
        }
      }
    }
    result.end();

    List<TagsMatcher> tagMatchers = new ArrayList<>();
    tagMatchers.add(defaultTags());
    tagMatchers.add(tag(SPAN_KIND, is(SPAN_KIND_INTERNAL)));
    if (value != null) {
      tagMatchers.add(tag(HTTP_STATUS, is(expectedStatus)));
    }

    assertTraces(
        trace(
            span().root().operationName("internal").tags(tagMatchers.toArray(new TagsMatcher[0]))));
  }

  static Map<String, String> attributes(String... keyValues) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  static Matcher<Object> isManuallySet() {
    return Matchers.validates(o -> ServiceNameSources.MANUAL.toString().equals(String.valueOf(o)));
  }
}
