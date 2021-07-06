package datadog.cws.tls;

import datadog.trace.api.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CwsTlsFactory {
  private static final Logger log = LoggerFactory.getLogger(CwsTlsFactory.class);

  public static CwsTls newCwsTls(int maxThreads) {
    if (Config.get().isCwsEnabled()) {
      if (CwsErpcTls.isSupported()) {
        int refresh = Config.get().getCwsTlsRefresh();
        return new CwsErpcTls(maxThreads, refresh);
      }
      log.warn("Cloud Workload Security integration not supported");
    }
    return new CwsNoTls();
  }
}
