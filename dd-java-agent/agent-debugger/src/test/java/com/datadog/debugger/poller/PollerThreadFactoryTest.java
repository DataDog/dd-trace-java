package com.datadog.debugger.poller;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.poller.PollerHttpClient.PollerThreadFactory;
import org.junit.jupiter.api.Test;

class PollerThreadFactoryTest {

  @Test
  void testFactoryCreatesDaemonThreads() {
    PollerThreadFactory pollerThreadFactory = new PollerThreadFactory("xyz");
    Thread thread = pollerThreadFactory.newThread(() -> {});
    assertTrue(thread.isDaemon());
  }
}
