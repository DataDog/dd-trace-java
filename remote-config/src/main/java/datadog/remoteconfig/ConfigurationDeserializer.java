package datadog.remoteconfig;

import java.io.IOException;

public interface ConfigurationDeserializer<T> {
  T deserialize(byte[] content) throws IOException;
}
