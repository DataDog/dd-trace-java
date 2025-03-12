package datadog.trace.instrumentation.googlepubsub;

import com.google.pubsub.v1.PubsubMessage;
import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class TextMapInjectAdapter implements CarrierSetter<PubsubMessage.Builder> {

  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final PubsubMessage.Builder msg, final String key, final String value) {
    msg.putAttributes(key, value);
  }
}
