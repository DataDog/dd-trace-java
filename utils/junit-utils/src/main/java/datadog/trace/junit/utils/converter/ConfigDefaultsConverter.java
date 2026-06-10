package datadog.trace.junit.utils.converter;

import static datadog.trace.junit.utils.converter.MapBasedConverter.handleMap;

import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.env.CapturedEnvironment;
import java.util.Map;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

public class ConfigDefaultsConverter implements ArgumentConverter {
  @Override
  public Object convert(Object source, ParameterContext context)
      throws ArgumentConversionException {
    Map<? super Object, ? super Object> map = handleMap(source, context, this);
    if (map != null) {
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
    return source.toString();
  }

}
