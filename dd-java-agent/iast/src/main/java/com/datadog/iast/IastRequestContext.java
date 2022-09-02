package com.datadog.iast;

import com.datadog.iast.model.VulnerabilityBatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class IastRequestContext {

  private final VulnerabilityBatch vulnerabilityBatch;

  private final AtomicBoolean spanDataIsSet;

  public IastRequestContext() {
    this.vulnerabilityBatch = new VulnerabilityBatch();
    this.spanDataIsSet = new AtomicBoolean(false);
  }

  public VulnerabilityBatch getVulnerabilityBatch() {
    return vulnerabilityBatch;
  }

  public boolean getAndSetSpanDataIsSet() {
    return spanDataIsSet.getAndSet(true);
  }
}
