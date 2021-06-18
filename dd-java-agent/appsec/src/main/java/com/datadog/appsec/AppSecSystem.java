package com.datadog.appsec;

import com.datadog.appsec.config.AppSecConfig;
import com.datadog.appsec.config.AppSecConfigFactory;
import datadog.trace.api.Config;
import java.io.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppSecSystem {

  private static final Logger log = LoggerFactory.getLogger(AppSecSystem.class);

  private AppSecSystem() {}

  public static void start() {
    Config config = Config.get();
    if (!config.isAppSecEnabled()) {
      log.debug("AppSec: disabled");
      return;
    }
    log.info("AppSec has started");

    try {
      // Read config from yaml file
      AppSecConfig appSecConfig =
          AppSecConfigFactory.fromYamlFile(new File(config.getAppSecConfigFile()));

      // Convert config to legacy json
      String json = AppSecConfigFactory.toLegacyFormat(appSecConfig);
      json.toString();

    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
  }
}
