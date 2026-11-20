package datadog.trace.api.iast;

import datadog.trace.api.iast.telemetry.IastMetric.Tag;
import java.lang.reflect.Field;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

/**
 * Resolves table cells to the {@link VulnerabilityTypes}, {@link SourceTypes} or {@link Tag}
 * constant they name. Numeric cells are parsed as {@code byte}, blank cells resolve to {@code
 * null}.
 */
public class IastConstantConverter implements ArgumentConverter {

  private static final Class<?>[] CLASSES = {
    VulnerabilityTypes.class, SourceTypes.class, Tag.class
  };

  @Override
  public Object convert(Object source, ParameterContext context) {
    if (source == null) {
      return null;
    }
    String name = (String) source;
    try {
      return Byte.parseByte(name);
    } catch (NumberFormatException ignored) {
      // not a number, resolve the constant name
    }
    for (Class<?> clazz : CLASSES) {
      try {
        Field field = clazz.getField(name);
        return field.get(null);
      } catch (NoSuchFieldException ignored) {
        // try the next class
      } catch (IllegalAccessException e) {
        throw new ArgumentConversionException("Cannot read constant " + name, e);
      }
    }
    throw new ArgumentConversionException("Unknown IAST constant " + name);
  }
}
