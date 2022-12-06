package datadog.cws.tls;

import datadog.trace.api.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TlsFactory {
  private static final Logger log = LoggerFactory.getLogger(TlsFactory.class);

  public static Tls newTls(int maxThreads) {
    if (Config.get().isCwsEnabled()) {
      if (ErpcTls.isSupported()) {
        int refresh = Config.get().getCwsTlsRefresh();
        return new ErpcTls(maxThreads, refresh);
      }
      log.warn("Cloud Workload Security integration not supported");
    }
    return new NoTls();
  }
}
