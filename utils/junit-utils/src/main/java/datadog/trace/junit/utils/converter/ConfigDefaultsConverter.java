package datadog.trace.junit.utils.converter;

import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME;

import datadog.trace.junit.utils.converter.AbstractClassConstantConvertor.AbstractStringFallThruConverter;
import java.util.HashMap;
import java.util.Map;

public class ConfigDefaultsConverter extends AbstractStringFallThruConverter {
  private static final Map<String, String> MAPPING;

  static {
    MAPPING = new HashMap<>();
    MAPPING.put("DEFAULT_SERVICE_NAME", DEFAULT_SERVICE_NAME);
    MAPPING.put(
        "DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME", DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME);
  }

  @Override
  protected String className() {
    return "ConfigDefaults";
  }

  @Override
  protected Map<String, String> mapping() {
    return MAPPING;
  }
}
