package datadog.trace.core.datastreams;

import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.PathwayContextHolder;
import java.util.concurrent.ThreadLocalRandom;

public class DefaultPathwayContextHolder implements PathwayContextHolder {
  private volatile PathwayContext pathwayContext;

  public DefaultPathwayContextHolder(PathwayContext pathwayContext) {
    this.pathwayContext = pathwayContext;
  }

  @Override
  public PathwayContext getPathwayContext() {
    return pathwayContext;
  }

  @Override
  public void mergePathwayContext(PathwayContext pathwayContext) {
    if (pathwayContext == null) {
      return;
    }

    // This is purposely not thread safe
    // The code randomly chooses between the two PathwayContexts.
    // If there is a race, then that's okay
    if (this.pathwayContext.isStarted()) {
      // Randomly select between keeping the current context (0) or replacing (1)
      if (ThreadLocalRandom.current().nextInt(2) == 1) {
        this.pathwayContext = pathwayContext;
      }
    } else {
      this.pathwayContext = pathwayContext;
    }
  }
}
