package datadog.trace.api.featureflag;

import java.io.IOException;

/** Internal, version-aligned extension boundary. It exposes no RC protocol or HTTP types. */
public interface RemoteConfigTransport extends AutoCloseable {
  @FunctionalInterface
  interface ConfigurationListener {
    /** A null configuration removes the current configuration. */
    void accept(byte[] configuration) throws IOException;
  }

  void start(ConfigurationListener listener);

  void post(String route, byte[] json, boolean responseCompression) throws IOException;

  @Override
  void close();
}
