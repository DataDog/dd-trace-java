package com.datadog.appsec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;

public class ConfigFactory {

  private static final ObjectMapper yamlMapper;
  private static final ObjectMapper jsonMapper;

  static {
    yamlMapper = new ObjectMapper(new YAMLFactory());

    jsonMapper = new ObjectMapper();
    SimpleModule module = new SimpleModule();
    module.addSerializer(AppSecConfig.class, new LegacyConfigSerializer());
    jsonMapper.registerModule(module);
  }

  private ConfigFactory() {}

  public static AppSecConfig fromYamlFile(File file) throws IOException {
    return yamlMapper.readValue(file, AppSecConfig.class);
  }

  public static String toLegacyFormat(AppSecConfig config) throws IOException {
    return jsonMapper.writeValueAsString(config);
  }
}
