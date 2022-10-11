package datadog.smoketest.jbossmodules.messaging;

import datadog.smoketest.jbossmodules.common.ServiceSupport;

public abstract class SubscriberSupport extends ServiceSupport {
  public final void receive(final Message message) {
    log.info("Receiving " + message);
    doReceive(message);
  }

  protected abstract void doReceive(final Message message);
}
