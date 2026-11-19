package datadog.smoketest.springboot.openfeature;

import com.datadog.openfeature.Provider;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.util.concurrent.CountDownLatch;
import org.springframework.stereotype.Component;

/**
 * Registers the provider on request, from a thread started outside any request trace, so the
 * provider initialization does not capture the trace context of the request.
 */
@Component
public class ProviderRegistration {
  private final CountDownLatch requested = new CountDownLatch(1);

  public ProviderRegistration() {
    final Thread thread =
        new Thread(this::registerWhenRequested, "openfeature-provider-registration");
    thread.setDaemon(true);
    thread.start();
  }

  /** Requests the provider registration, without waiting for its initialization. */
  public void request() {
    this.requested.countDown();
  }

  private void registerWhenRequested() {
    try {
      this.requested.await();
      OpenFeatureAPI.getInstance().setProvider(new Provider());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
