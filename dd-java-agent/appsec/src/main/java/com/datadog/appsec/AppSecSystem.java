package com.datadog.appsec;

import com.datadog.appsec.config.AppSecConfig;
import com.datadog.appsec.config.ConfigFactory;
import datadog.trace.api.Config;
import java.io.File;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecSystem {

  private static final Logger log = LoggerFactory.getLogger(AppSecSystem.class);

  public static void start() {
    Config config = Config.get();
    if (!config.isAppSecEnabled()) {
      log.debug("AppSec: disabled");
      return;
    }
    log.info("AppSec has started");

    try {
      // Read config from yaml file
      AppSecConfig appSecConf = ConfigFactory.fromYamlFile(new File(config.getAppSecConfigFile()));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
