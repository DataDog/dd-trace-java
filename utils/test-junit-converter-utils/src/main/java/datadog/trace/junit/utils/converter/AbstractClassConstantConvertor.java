package datadog.trace.junit.utils.converter;

import java.util.Map;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

public abstract class AbstractClassConstantConvertor<T> implements ArgumentConverter {
  /**
   * Returns the class name (simple class name, not fully qualified) of the constants to convert.
   *
   * @return The simple class name.
   */
  protected abstract String className();

  /**
   * Returns the constant mapping between their string representations to their values.
   *
   * @return The constant mapping between their string representations to their values.
   */
  protected abstract Map<String, T> mapping();

  @Override
  public T convert(Object source, ParameterContext context) {
    if (source == null) {
      return null;
    }
    String className = className();
    int length = className.length();
    String s = source.toString();
    if (s.startsWith(className) && s.charAt(length) == '.') {
      s = s.substring(length + 1);
    }
    T mappedValue = mapping().get(s);
    if (mappedValue == null && throwsOnUnsupportedValue()) {
      throw new ArgumentConversionException(
          "Unsupported constant " + source + " from " + className);
    }
    return mappedValue;
  }

  protected boolean throwsOnUnsupportedValue() {
    return true;
  }

  public abstract static class AbstractStringFallThruConverter
      extends AbstractClassConstantConvertor<String> {
    @Override
    public String convert(Object source, ParameterContext context) {
      String convert = super.convert(source, context);
      return convert == null ? source.toString() : convert;
    }

    @Override
    protected boolean throwsOnUnsupportedValue() {
      return false;
    }
  }
}
