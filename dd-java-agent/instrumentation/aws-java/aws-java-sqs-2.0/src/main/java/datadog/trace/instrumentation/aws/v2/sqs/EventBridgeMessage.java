package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;

import datadog.json.JsonMapper;
import datadog.trace.api.datastreams.DataStreamsTags;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

final class EventBridgeMessage {
  private static final Logger log = LoggerFactory.getLogger(EventBridgeMessage.class);

  private static final String DATADOG_KEY = "_datadog";
  private static final String DETAIL_TYPE_KEY = "detail-type";
  private static final String DETAIL_KEY = "detail";
  private static final String RESOURCE_NAME_KEY = "x-datadog-resource-name";
  private static final String DEFAULT_EVENT_BUS_NAME = "default";
  private static final String ARN_EVENT_BUS_PREFIX = "event-bus/";
  private static final String TYPE = "eventbridge";
  private static final int MAX_NESTING_DEPTH = 512;

  private final Map<String, ?> datadogAttributes;
  private final String eventBusName;
  private final String detailType;

  private EventBridgeMessage(
      Map<String, ?> datadogAttributes, String eventBusName, String detailType) {
    this.datadogAttributes = datadogAttributes;
    this.eventBusName = eventBusName;
    this.detailType = detailType;
  }

  Map<String, ?> datadogAttributes() {
    return datadogAttributes;
  }

  static EventBridgeMessage tryParseFrom(Message message) {
    if (null == message) {
      return null;
    }
    Map<String, MessageAttributeValue> attributes = message.messageAttributes();
    if (null != attributes && attributes.containsKey(DATADOG_KEY)) {
      return null;
    }
    String body = message.body();
    if (null == body || body.isEmpty() || '{' != body.charAt(0)) {
      return null;
    }
    if (!body.contains('"' + DETAIL_TYPE_KEY + '"')
        || !body.contains('"' + DETAIL_KEY + '"')
        || !body.contains('"' + DATADOG_KEY + '"')) {
      return null;
    }
    if (exceedsMaxNestingDepth(body)) {
      log.debug(
          "Skipping EventBridge envelope with JSON nesting deeper than {}", MAX_NESTING_DEPTH);
      return null;
    }
    try {
      Map<String, Object> envelope = JsonMapper.fromJsonToMap(body);
      Object detailType = envelope.get(DETAIL_TYPE_KEY);
      Object detail = envelope.get(DETAIL_KEY);
      if (!(detailType instanceof String) || !(detail instanceof Map)) {
        return null;
      }
      Object datadog = ((Map<?, ?>) detail).get(DATADOG_KEY);
      if (!(datadog instanceof Map) || ((Map<?, ?>) datadog).isEmpty()) {
        return null;
      }
      Object resourceName = ((Map<?, ?>) datadog).get(RESOURCE_NAME_KEY);
      String eventBusName = resourceName instanceof String ? (String) resourceName : null;
      return new EventBridgeMessage((Map<String, ?>) datadog, eventBusName, (String) detailType);
    } catch (Exception e) {
      log.debug("Unable to parse EventBridge envelope from SQS message body", e);
      return null;
    }
  }

  DataStreamsTags consumeTags() {
    String exchange = normalizeEventBusName(eventBusName);
    String topic = null == detailType || detailType.isEmpty() ? null : detailType;
    return new DataStreamsTags(
        null, INBOUND, exchange, topic, TYPE, null, null, null, null, null, null, null, null, null);
  }

  private static boolean exceedsMaxNestingDepth(String body) {
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if ('\\' == c) {
          escaped = true;
        } else if ('"' == c) {
          inString = false;
        }
      } else if ('"' == c) {
        inString = true;
      } else if ('{' == c || '[' == c) {
        if (++depth > MAX_NESTING_DEPTH) {
          return true;
        }
      } else if ('}' == c || ']' == c) {
        depth--;
      }
    }
    return false;
  }

  private static String normalizeEventBusName(String eventBusName) {
    if (null == eventBusName || eventBusName.isEmpty()) {
      return DEFAULT_EVENT_BUS_NAME;
    }
    int arnBusNameStart = eventBusName.indexOf(ARN_EVENT_BUS_PREFIX);
    if (arnBusNameStart >= 0) {
      return eventBusName.substring(arnBusNameStart + ARN_EVENT_BUS_PREFIX.length());
    }
    return eventBusName;
  }
}
