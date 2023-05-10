package com.datadog.iast.overhead;

import static datadog.trace.api.iast.IastDetectionMode.UNLIMITED;

import com.datadog.iast.util.NonBlockingSemaphore;
import datadog.trace.api.Config;

public class OverheadContext {

  private final NonBlockingSemaphore availableVulnerabilities;

  public OverheadContext() {
    this(Config.get().getIastVulnerabilitiesPerRequest());
  }

  OverheadContext(final int vulnerabilitiesPerRequest) {
    availableVulnerabilities =
        vulnerabilitiesPerRequest == UNLIMITED
            ? NonBlockingSemaphore.unlimited()
            : NonBlockingSemaphore.withPermitCount(vulnerabilitiesPerRequest);
  }

  public int getAvailableQuota() {
    return availableVulnerabilities.available();
  }

  public boolean consumeQuota(final int delta) {
    return availableVulnerabilities.acquire(delta);
  }

  public void reset() {
    availableVulnerabilities.reset();
  }
}
