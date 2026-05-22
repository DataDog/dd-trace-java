package datadog.trace.junit.utils.tabletest;

import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.env.CapturedEnvironment;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

public class ConfigDefaultsConverter implements ArgumentConverter {
  @Override
  public Object convert(Object source, ParameterContext context)
      throws ArgumentConversionException {
    if (source instanceof Map) {
      // convert keys and values from the map
      Map<? super Object, ? super Object> map = new HashMap<>();
      for (Map.Entry<? super Object, ? super Object> e :
          ((Map<? super Object, ? super Object>) source).entrySet()) {
        map.put(convert(e.getKey(), context), convert(e.getValue(), context));
      }
      return map;
    }
    if (source.toString().startsWith("DEFAULT_")) {
      switch (source.toString()) {
        case "DEFAULT_SERVICE_NAME":
          return ConfigDefaults.DEFAULT_SERVICE_NAME;
        case "DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME":
          return ConfigDefaults.DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME;
        default:
          throw new ArgumentConversionException("Cannot convert " + source);
      }
    }
    if ("ENV_SERVICE_NAME".equals(source.toString())) {
      return CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME);
    }
    return source.toString();
  }
}
