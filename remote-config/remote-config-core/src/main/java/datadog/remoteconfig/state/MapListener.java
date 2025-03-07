package datadog.remoteconfig.state;

import datadog.remoteconfig.ConfigurationMapListener;
import datadog.remoteconfig.PollingRateHinter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Map;

public class MapListener implements ProductListener {
  private final ConfigurationMapListener listener;

  public MapListener(ConfigurationMapListener listener) {
    this.listener = listener;
  }

  @Override
  public void accept(ConfigKey configKey, byte[] content, PollingRateHinter pollingRateHinter)
      throws IOException {
    if (content != null) {
      listener.accept(configKey.toString(), deserialize(content), pollingRateHinter);
    }
  }

  public void accept(ConfigKey configKey, Map<String, Object> content) throws IOException {
    listener.accept(configKey.toString(), content, null);
  }

  @Override
  public void remove(ConfigKey configKey, PollingRateHinter pollingRateHinter) throws IOException {
    listener.accept(configKey.toString(), null, pollingRateHinter);
  }

  @Override
  public void commit(PollingRateHinter pollingRateHinter) {}

  private Map<String, Object> deserialize(byte[] content) throws IOException {
    // Parse byte array to Map
    ByteArrayInputStream byteIn = new ByteArrayInputStream(content);
    ObjectInputStream in = new ObjectInputStream(byteIn);
    try {
      return (Map<String, Object>) in.readObject();
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }
}
