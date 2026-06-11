package datadog.trace.junit.utils.converter;

import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.ParameterContext;

public class ConfigDefaultsConverter extends AbstractClassConstantConvertor<String> {
  private static final Map<String, String> MAPPING;

  static {
    MAPPING = new HashMap<>();
    MAPPING.put("DEFAULT_SERVICE_NAME", DEFAULT_SERVICE_NAME);
    MAPPING.put("DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME", DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME);
  }

  @Override
  protected String className() {
    return "ConfigDefaults";
  }

  @Override
  protected Map<String, String> mapping() {
    return MAPPING;
  }

  @Override
  protected boolean throwsOnUnsupportedValue() {
    return false;
  }

  @Override
  public String convert(Object source, ParameterContext context) {
    String convert = super.convert(source, context);
    return convert == null ? source.toString() : convert;
  }
}
