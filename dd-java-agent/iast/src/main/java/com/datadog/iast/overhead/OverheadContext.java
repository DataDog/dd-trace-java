package com.datadog.iast.overhead;

import datadog.trace.api.Config;
import java.util.concurrent.atomic.AtomicInteger;

public class OverheadContext {

  private static final int VULNERABILITIES_PER_REQUEST =
      Config.get().getIastVulnerabilitiesPerRequest();

  private final AtomicInteger availableVulnerabilities =
      new AtomicInteger(VULNERABILITIES_PER_REQUEST);

  public int getAvailableQuota() {
    return availableVulnerabilities.get();
  }

  public boolean consumeQuota(final int delta) {
    final int beforeUpdate =
        availableVulnerabilities.getAndAccumulate(delta, (v, d) -> (v < d) ? v : v - d);
    return beforeUpdate >= delta;
  }

  public void reset() {
    availableVulnerabilities.set(VULNERABILITIES_PER_REQUEST);
  }
}
