package datadog.trace.instrumentation.opentelemetry14.trace;

import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_PRODUCER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;
import static io.opentelemetry.api.trace.SpanKind.CLIENT;
import static io.opentelemetry.api.trace.SpanKind.CONSUMER;
import static io.opentelemetry.api.trace.SpanKind.INTERNAL;
import static io.opentelemetry.api.trace.SpanKind.PRODUCER;
import static io.opentelemetry.api.trace.SpanKind.SERVER;
import static java.util.Locale.ROOT;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentelemetry.api.trace.SpanKind;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OtelConventions {
  static final String SPAN_KIND_INTERNAL = "internal";
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelConventions.class);
  private static final String OPERATION_NAME_SPECIFIC_ATTRIBUTE = "operation.name";
  private static final String SERVICE_NAME_SPECIFIC_ATTRIBUTE = "service.name";
  private static final String RESOURCE_NAME_SPECIFIC_ATTRIBUTE = "resource.name";
  private static final String SPAN_TYPE_SPECIFIC_ATTRIBUTES = "span.type";
  private static final String ANALYTICS_EVENT_SPECIFIC_ATTRIBUTES = "analytics.event";

  private OtelConventions() {}

  /**
   * Convert OpenTelemetry {@link SpanKind} to Datadog span type.
   *
   * @param spanKind The OpenTelemetry span kind to convert.
   * @return The related Datadog span type.
   */
  public static String toSpanType(SpanKind spanKind) {
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
   * Convert Datadog span type to OpenTelemetry {@link SpanKind}.
   *
   * @param spanType The span type to convert.
   * @return The related OpenTelemetry {@link SpanKind}.
   */
  public static SpanKind toSpanKind(String spanType) {
    if (spanType == null) {
      return INTERNAL;
    }
    switch (spanType) {
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

  public static void applyConventions(AgentSpan span) {
    String serviceName = getStringAttribute(span, SERVICE_NAME_SPECIFIC_ATTRIBUTE);
    if (serviceName != null) {
      span.setServiceName(serviceName);
    }
    String resourceName = getStringAttribute(span, RESOURCE_NAME_SPECIFIC_ATTRIBUTE);
    if (resourceName != null) {
      span.setResourceName(resourceName);
    }
    String spanType = getStringAttribute(span, SPAN_TYPE_SPECIFIC_ATTRIBUTES);
    if (spanType != null) {
      span.setSpanType(spanType);
    }
    Boolean analyticsEvent = getBooleanAttribute(span, ANALYTICS_EVENT_SPECIFIC_ATTRIBUTES);
    if (analyticsEvent != null) {
      span.setMetric(ANALYTICS_SAMPLE_RATE, analyticsEvent ? 1 : 0);
    }
    applyOperationName(span);
  }

  private static void applyOperationName(AgentSpan span) {
    String operationName = getStringAttribute(span, OPERATION_NAME_SPECIFIC_ATTRIBUTE);
    if (operationName == null) {
      operationName = computeOperationName(span);
    }
    span.setOperationName(operationName.toLowerCase(ROOT));
  }

  private static String computeOperationName(AgentSpan span) {
    SpanKind spanKind = toSpanKind(span.getSpanType());
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

  @Nullable
  private static Boolean getBooleanAttribute(AgentSpan span, String key) {
    Object tag = span.getTag(key);
    if (tag == null) {
      return null;
    }
    if (tag instanceof Boolean) {
      return (Boolean) tag;
    } else if (tag instanceof String) {
      return Boolean.parseBoolean((String) tag);
    } else {
      LOGGER.debug("Span attributes {} is not a boolean", key);
      return null;
    }
  }
}
