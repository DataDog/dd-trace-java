package com.datadog.appsec;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.Config;
import java.util.concurrent.atomic.AtomicBoolean;

import datadog.trace.api.gateway.InstrGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecSystem {

  private static final Logger log = LoggerFactory.getLogger(AppSecSystem.class);
  private static final AtomicBoolean STARTED = new AtomicBoolean();

  public static void start(InstrGateway gw) {
    final Config config = Config.get();
    if (!config.isAppSecEnabled()) {
      log.debug("AppSec: disabled");
      return;
    }
    log.info("AppSec has started");
    STARTED.set(true);

    gw.registerRequestContextFactory(AppSecRequestContext::new);
  }

  public static boolean isStarted() {
    return STARTED.get();
  }
}
