package com.datadog.appsec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;

public class ConfigFactory {

  private static final ObjectMapper yamlMapper;

  static {
    yamlMapper = new ObjectMapper(new YAMLFactory());
  }

  private ConfigFactory() {}

  public static AppSecConfig fromYamlFile(File file) throws IOException {
    return yamlMapper.readValue(file, AppSecConfig.class);
  }
}
