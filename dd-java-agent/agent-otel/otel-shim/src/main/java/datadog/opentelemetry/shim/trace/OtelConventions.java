package datadog.opentelemetry.shim.trace;

import static datadog.opentelemetry.shim.trace.OtelSpanEvent.EXCEPTION_MESSAGE_ATTRIBUTE_KEY;
import static datadog.opentelemetry.shim.trace.OtelSpanEvent.EXCEPTION_STACK_TRACE_ATTRIBUTE_KEY;
import static datadog.opentelemetry.shim.trace.OtelSpanEvent.EXCEPTION_TYPE_ATTRIBUTE_KEY;
import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.DDTags.ERROR_MSG;
import static datadog.trace.api.DDTags.ERROR_STACK;
import static datadog.trace.api.DDTags.ERROR_TYPE;
import static datadog.trace.api.DDTags.SPAN_EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_PRODUCER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;
import static io.opentelemetry.api.trace.SpanKind.CLIENT;
import static io.opentelemetry.api.trace.SpanKind.CONSUMER;
import static io.opentelemetry.api.trace.SpanKind.INTERNAL;
import static io.opentelemetry.api.trace.SpanKind.PRODUCER;
import static io.opentelemetry.api.trace.SpanKind.SERVER;
import static java.lang.Boolean.parseBoolean;
import static java.util.Locale.ROOT;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OtelConventions {
  static final String SPAN_KIND_INTERNAL = "internal";
  static final String OPERATION_NAME_SPECIFIC_ATTRIBUTE = "operation.name";
  static final String SPAN_TYPE = "span.type";
  static final String ANALYTICS_EVENT_SPECIFIC_ATTRIBUTES = "analytics.event";
  static final String HTTP_RESPONSE_STATUS_CODE_ATTRIBUTE = "http.response.status_code";

  private static final Logger LOGGER = LoggerFactory.getLogger(OtelConventions.class);

  private OtelConventions() {}

  /**
   * Convert OpenTelemetry {@link SpanKind} to {@link Tags#SPAN_KIND} value.
   *
   * @param spanKind The OpenTelemetry span kind to convert.
   * @return The {@link Tags#SPAN_KIND} value.
   */
  public static String toSpanKindTagValue(SpanKind spanKind) {
    switch (spanKind) {
      case CLIENT:
        return SPAN_KIND_CLIENT;
      case SERVER:
        return SPAN_KIND_SERVER;
      case PRODUCER:
        return SPAN_KIND_PRODUCER;
      case CONSUMER:
        return SPAN_KIND_CONSUMER;
      case INTERNAL:
        return SPAN_KIND_INTERNAL;
      default:
        return spanKind.toString().toLowerCase(ROOT);
    }
  }

  /**
   * Convert {@link Tags#SPAN_KIND} value to OpenTelemetry {@link SpanKind}.
   *
   * @param spanKind The {@link Tags#SPAN_KIND} value to convert.
   * @return The related OpenTelemetry {@link SpanKind}.
   */
  public static SpanKind toOtelSpanKind(String spanKind) {
    if (spanKind == null) {
      return INTERNAL;
    }
    switch (spanKind) {
      case SPAN_KIND_CLIENT:
        return CLIENT;
      case SPAN_KIND_SERVER:
        return SERVER;
      case SPAN_KIND_PRODUCER:
        return PRODUCER;
      case SPAN_KIND_CONSUMER:
        return CONSUMER;
      default:
        return INTERNAL;
    }
  }

  /**
   * Applies the reserved span attributes. Only OpenTelemetry specific span attributes are handled
   * here, the default ones are handled by tag interceptor while setting span attributes.
   *
   * @param span The span to apply the attributes.
   * @param key The attribute key.
   * @param value The attribute value.
   * @param <T> The attribute type.
   * @return {@code true} if the attributes is a reserved attribute applied to the span, {@code
   *     false} otherwise.
   */
  public static <T> boolean applyReservedAttribute(AgentSpan span, AttributeKey<T> key, T value) {
    String name = key.getKey();
    switch (key.getType()) {
      case STRING:
        if (OPERATION_NAME_SPECIFIC_ATTRIBUTE.equals(name) && value instanceof String) {
          span.setOperationName(((String) value).toLowerCase(ROOT));
          return true;
        } else if (ANALYTICS_EVENT_SPECIFIC_ATTRIBUTES.equals(name) && value instanceof String) {
          span.setMetric(ANALYTICS_SAMPLE_RATE, parseBoolean((String) value) ? 1 : 0);
          return true;
        } else if (SPAN_TYPE.equals(name) && value instanceof String) {
          span.setSpanType((CharSequence) value);
          return true;
        }
      case BOOLEAN:
        if (ANALYTICS_EVENT_SPECIFIC_ATTRIBUTES.equals(name) && value instanceof Boolean) {
          span.setMetric(ANALYTICS_SAMPLE_RATE, ((Boolean) value) ? 1 : 0);
          return true;
        }
      case LONG:
        if (HTTP_RESPONSE_STATUS_CODE_ATTRIBUTE.equals(name) && value instanceof Number) {
          span.setHttpStatusCode(((Number) value).intValue());
          return true;
        }
    }
    return false;
  }

  public static void applyNamingConvention(AgentSpan span) {
    // Check if span operation name is unchanged from its default value
    if (span.getOperationName() == SPAN_KIND_INTERNAL) {
      span.setOperationName(computeOperationName(span).toLowerCase(ROOT));
    }
  }

  public static void setEventsAsTag(AgentSpan span, List<OtelSpanEvent> events) {
    if (events == null || events.isEmpty()) {
      return;
    }
    span.setTag(SPAN_EVENTS, OtelSpanEvent.toTag(events));
  }

  public static void applySpanEventExceptionAttributesAsTags(
      AgentSpan span, Attributes exceptionAttributes) {
    span.setTag(ERROR_MSG, exceptionAttributes.get(EXCEPTION_MESSAGE_ATTRIBUTE_KEY));
    span.setTag(ERROR_TYPE, exceptionAttributes.get(EXCEPTION_TYPE_ATTRIBUTE_KEY));
    span.setTag(ERROR_STACK, exceptionAttributes.get(EXCEPTION_STACK_TRACE_ATTRIBUTE_KEY));
  }

  private static String computeOperationName(AgentSpan span) {
    Object spanKingTag = span.getTag(SPAN_KIND);
    SpanKind spanKind =
        spanKingTag instanceof String ? toOtelSpanKind((String) spanKingTag) : INTERNAL;
    /*
     * HTTP convention: https://opentelemetry.io/docs/specs/otel/trace/semantic_conventions/http/
     */
    String httpRequestMethod = getStringAttribute(span, "http.request.method");
    if (spanKind == SERVER && httpRequestMethod != null) {
      return "http.server.request";
    }
    if (spanKind == CLIENT && httpRequestMethod != null) {
      return "http.client.request";
    }
    /*
     * Database convention: https://opentelemetry.io/docs/specs/semconv/database/database-spans/
     */
    String dbSystem = getStringAttribute(span, "db.system");
    if (spanKind == CLIENT && dbSystem != null) {
      return dbSystem + ".query";
    }
    /*
     * Messaging: https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/
     */
    String messagingSystem = getStringAttribute(span, "messaging.system");
    String messagingOperation = getStringAttribute(span, "messaging.operation");
    if ((spanKind == CONSUMER || spanKind == PRODUCER || spanKind == CLIENT || spanKind == SERVER)
        && messagingSystem != null
        && messagingOperation != null) {
      return messagingSystem + "." + messagingOperation;
    }
    /*
     * AWS: https://opentelemetry.io/docs/specs/semconv/cloud-providers/aws-sdk/
     */
    String rpcSystem = getStringAttribute(span, "rpc.system");
    if (spanKind == CLIENT && "aws-api".equals(rpcSystem)) {
      String service = getStringAttribute(span, "rpc.service");
      if (service == null) {
        return "aws.client.request";
      } else {
        return "aws." + service + ".request";
      }
    }
    /*
     * RPC: https://opentelemetry.io/docs/specs/semconv/rpc/rpc-spans/
     */
    if (spanKind == CLIENT && rpcSystem != null) {
      return rpcSystem + ".client.request";
    }
    if (spanKind == SERVER && rpcSystem != null) {
      return rpcSystem + ".server.request";
    }
    /*
     * FaaS:
     * https://opentelemetry.io/docs/specs/semconv/faas/faas-spans/#incoming-faas-span-attributes
     * https://opentelemetry.io/docs/specs/semconv/faas/faas-spans/#outgoing-invocations
     */
    String faasInvokedProvider = getStringAttribute(span, "faas.invoked_provider");
    String faasInvokedName = getStringAttribute(span, "faas.invoked_name");
    if (spanKind == CLIENT && faasInvokedProvider != null && faasInvokedName != null) {
      return faasInvokedProvider + "." + faasInvokedName + ".invoke";
    }
    String faasTrigger = getStringAttribute(span, "faas.trigger");
    if (spanKind == SERVER && faasTrigger != null) {
      return faasTrigger + ".invoke";
    }
    /*
     * GraphQL: https://opentelemetry.io/docs/specs/otel/trace/semantic_conventions/instrumentation/graphql/
     */
    String graphqlOperationType = getStringAttribute(span, "graphql.operation.type");
    if (graphqlOperationType != null) {
      return "graphql.server.request";
    }
    /*
     * Generic server / client: https://opentelemetry.io/docs/specs/semconv/http/http-spans/
     */
    String networkProtocolName = getStringAttribute(span, "network.protocol.name");
    if (spanKind == SERVER) {
      return networkProtocolName == null
          ? "server.request"
          : networkProtocolName + ".server.request";
    }
    if (spanKind == CLIENT) {
      return networkProtocolName == null
          ? "client.request"
          : networkProtocolName + ".client.request";
    }
    // Fallback if no convention match
    return spanKind.name();
  }

  @Nullable
  private static String getStringAttribute(AgentSpan span, String key) {
    Object tag = span.getTag(key);
    if (tag == null) {
      return null;
    } else if (!(tag instanceof String)) {
      LOGGER.debug("Span attributes {} is not a string", key);
      return key;
    }
    return (String) tag;
  }

  public static SpanAttributes convertAttributes(Attributes attributes) {
    if (attributes.isEmpty()) {
      return SpanAttributes.EMPTY;
    }
    SpanAttributes.Builder builder = SpanAttributes.builder();
    attributes.forEach(
        (attributeKey, value) -> {
          String key = attributeKey.getKey();
          switch (attributeKey.getType()) {
            case STRING:
              builder.put(key, (String) value);
              break;
            case BOOLEAN:
              builder.put(key, (boolean) value);
              break;
            case LONG:
              builder.put(key, (long) value);
              break;
            case DOUBLE:
              builder.put(key, (double) value);
              break;
            case STRING_ARRAY:
              //noinspection unchecked
              builder.putStringArray(key, (List<String>) value);
              break;
            case BOOLEAN_ARRAY:
              //noinspection unchecked
              builder.putBooleanArray(key, (List<Boolean>) value);
              break;
            case LONG_ARRAY:
              //noinspection unchecked
              builder.putLongArray(key, (List<Long>) value);
              break;
            case DOUBLE_ARRAY:
              //noinspection unchecked
              builder.putDoubleArray(key, (List<Double>) value);
              break;
          }
        });
    return builder.build();
  }
}
