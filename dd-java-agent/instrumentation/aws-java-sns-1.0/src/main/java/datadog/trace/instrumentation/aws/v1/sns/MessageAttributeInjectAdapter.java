package datadog.trace.instrumentation.aws.v1.sns;

import com.amazonaws.services.sns.model.MessageAttributeValue;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class MessageAttributeInjectAdapter
    implements AgentPropagation.Setter<Map<String, MessageAttributeValue>> {

  public static final MessageAttributeInjectAdapter SETTER = new MessageAttributeInjectAdapter();

  @Override
  public void set(Map<String, MessageAttributeValue> carrier, String key, String value) {
    carrier.put(
        key,
        new MessageAttributeValue()
            .withDataType(
                "Binary") // Use Binary since SNS subscription filter policies fail silently
            // with JSON strings
            // https://github.com/DataDog/datadog-lambda-js/pull/269
            .withBinaryValue(ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8))));
  }
}
