package datadog.communication.fleet;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

public interface FleetService extends Closeable {
  void init();

  interface FleetSubscription {
    void cancel();
  }

  FleetSubscription subscribe(Product product, ConfigurationListener listener);

  @Override
  void close() throws IOException;

  interface ConfigurationListener {
    void onNewConfiguration(InputStream config);
  }

  enum Product {
    DEBUGGING(1),
    APPSEC(2),
    RUNTIME_SECURITY(3);

    public final int ordinal;

    Product(int ordinal) {
      this.ordinal = ordinal;
    }
  }
}
