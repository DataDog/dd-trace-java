package datadog.flare;

import datadog.communication.ddagent.SharedCommunicationObjects;

public final class TracerFlareSystem {
  private static TracerFlarePoller tracerFlarePoller;

  public static void doStart(SharedCommunicationObjects sco) {
    tracerFlarePoller = new TracerFlarePoller();
    tracerFlarePoller.start(sco);
  }

  public static void doStop() {
    tracerFlarePoller.stop();
  }
}
