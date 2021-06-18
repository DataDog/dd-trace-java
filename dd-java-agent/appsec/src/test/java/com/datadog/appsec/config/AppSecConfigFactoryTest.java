package com.datadog.appsec.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * TODO: This class for development purpose only and should be removed later
 */
class AppSecConfigFactoryTest {

  @Test
  public void test() throws FileNotFoundException {
    File file = new File("/Users/valentin/work/DataDog/dd-trace-java/dd-java-agent/appsec/configs/appsec_config.yaml");
    AppSecConfig config = AppSecConfigFactory.fromYamlFile(file);

    String json = AppSecConfigFactory.toLegacyFormat(config);
    json.toString();
  }
}
