package com.datadog.appsec.config;

import org.junit.jupiter.api.Test;

import java.io.*;

/**
 * TODO: This class for development purpose only and should be removed later
 */
class AppSecConfigFactoryTest {

  @Test
  public void test() throws IOException {
    File file = new File("/Users/valentin/work/DataDog/dd-trace-java/dd-java-agent/appsec/configs/appsec_config.yaml");
    AppSecConfig config = AppSecConfigFactory.fromYamlFile(file);

    String json = AppSecConfigFactory.toLegacyFormat(config);
    BufferedWriter writer = new BufferedWriter(new FileWriter(new File("/Users/valentin/work/DataDog/dd-trace-java/dd-java-agent/appsec/configs/gen3.json")));
    writer.write(json);
    writer.close();
  }
}
