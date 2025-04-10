package com.datadog.crashtracking;

import static datadog.communication.monitor.DDAgentStatsDClientManager.statsDClientManager;

import datadog.trace.api.StatsDClient;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OOMENotifier {
  private static final Logger log = LoggerFactory.getLogger(OOMENotifier.class);

  // This method is called via CLI so we don't need to be paranoid about the forbiddend APIs
  @SuppressForbidden
  public static void sendOomeEvent(String taglist) throws Exception {
    try (StatsDClient client =
        statsDClientManager().statsDClient(null, null, null, null, null, false)) {
      String[] tags = taglist.split(",");
      client.recordEvent(
          "error",
          "java",
          "OutOfMemoryError",
          "Java process encountered out of memory error",
          tags);
      log.info("OOME event sent");
      Thread.sleep(2 * 1000); // wait 2s to allow statsd client flushing the event
    }
  }
}
