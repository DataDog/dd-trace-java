package datadog.trace.instrumentation.googlepubsub;

import com.google.pubsub.v1.PubsubMessage;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.charset.StandardCharsets;

public class TextMapInjectAdapter
    implements AgentPropagation.Setter<PubsubMessage.Builder>,
        AgentPropagation.BinarySetter<PubsubMessage.Builder> {

  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final PubsubMessage.Builder msg, final String key, final String value) {
    msg.putAttributes(key, value);
  }

  @Override
  public void set(PubsubMessage.Builder msg, String key, byte[] value) {
    msg.putAttributes(key, new String(value, StandardCharsets.UTF_8));
  }
}
