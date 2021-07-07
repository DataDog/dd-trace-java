package datadog.communication.fleet;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

public interface FleetService extends Closeable {
  void init();

  // TODO: if the agent http service is polling-only, this should just return the results
  // Alternatively, the listener pattern can be kept and the dedicated thread/retry logic
  // moved from AppSecConfigServiceImpl here, so that it can be shared among fleet products
  // exception-safe
  void subscribe(Product product, ConfigurationListener listener);

  @Override
  void close() throws IOException;

  interface ConfigurationListener {
    void onNewConfiguration(InputStream config);

    void onError(Throwable t);

    void onCompleted();
  }

  enum Product {
    APPSEC(2);

    public final int ordinal;

    Product(int ordinal) {
      this.ordinal = ordinal;
    }
  }
}
