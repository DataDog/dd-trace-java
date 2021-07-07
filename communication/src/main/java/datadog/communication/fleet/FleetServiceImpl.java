package datadog.communication.fleet;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FleetServiceImpl implements FleetService {

  private static final Logger log = LoggerFactory.getLogger(FleetServiceImpl.class);

  @Override
  public void init() {
    log.debug("Fleet management not currently implemented");
  }

  // exception-safe
  @Override
  public void subscribe(Product product, final ConfigurationListener listener) {
    try {
      doSubscribe(product, listener);
    } catch (RuntimeException | Error rte) {
      log.error("Error on call to doSubscribe", rte);
      listener.onError(rte);
    }
  }

  private void doSubscribe(Product product, final ConfigurationListener listener) {
    // not implemented yet
  }

  @Override
  public void close() throws IOException {
    // not implemented yet
  }
}
