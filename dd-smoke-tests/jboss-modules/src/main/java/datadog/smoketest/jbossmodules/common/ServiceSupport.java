package datadog.smoketest.jbossmodules.common;

import static com.google.common.base.Preconditions.checkState;

public abstract class ServiceSupport extends LogSupport {
  private boolean started;

  public final synchronized void start() throws Exception {
    checkState(!started);
    try {
      log.info("Starting " + this);
      doStart();
      log.info("Started " + this);
      started = true;
    } catch (Exception e) {
      log.warn("Problem starting {}" + this, e);
      throw e;
    }
  }

  public final synchronized void stop() throws Exception {
    checkState(started);
    try {
      log.info("Stopping " + this);
      doStop();
      log.info("Stopped " + this);
      started = false;
    } catch (Exception e) {
      log.warn("Problem stopping {}" + this, e);
      throw e;
    }
  }

  protected abstract void doStart() throws Exception;

  protected abstract void doStop() throws Exception;
}
