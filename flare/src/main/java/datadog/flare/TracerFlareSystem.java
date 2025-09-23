package datadog.flare;

import datadog.communication.ddagent.SharedCommunicationObjects;

public final class TracerFlareSystem {
  private static TracerFlarePoller tracerFlarePoller;

  public static void start(SharedCommunicationObjects sco) {
    tracerFlarePoller = new TracerFlarePoller();
    tracerFlarePoller.doStart(sco);
  }

  public static void stop() {
    if (null != tracerFlarePoller) {
      tracerFlarePoller.doStop();
    }
  }
}
