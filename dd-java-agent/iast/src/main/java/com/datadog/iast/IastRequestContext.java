package com.datadog.iast;

import com.datadog.iast.model.VulnerabilityBatch;
import com.datadog.iast.overhead.OverheadContext;
import java.util.concurrent.atomic.AtomicBoolean;

public class IastRequestContext {

  private final VulnerabilityBatch vulnerabilityBatch;

  private final AtomicBoolean spanDataIsSet;

  private final OverheadContext overheadContext;

  public IastRequestContext() {
    this.vulnerabilityBatch = new VulnerabilityBatch();
    this.spanDataIsSet = new AtomicBoolean(false);
    this.overheadContext = new OverheadContext();
  }

  public VulnerabilityBatch getVulnerabilityBatch() {
    return vulnerabilityBatch;
  }

  public boolean getAndSetSpanDataIsSet() {
    return spanDataIsSet.getAndSet(true);
  }

  public OverheadContext getOverheadContext() {
    return overheadContext;
  }
}
