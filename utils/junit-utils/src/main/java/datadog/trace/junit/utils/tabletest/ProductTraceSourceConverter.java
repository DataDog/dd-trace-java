package datadog.trace.junit.utils.tabletest;

import datadog.trace.api.ProductTraceSource;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

public class ProductTraceSourceConverter implements ArgumentConverter {

  @Override
  public Object convert(Object source, ParameterContext context)
      throws ArgumentConversionException {
    if (source == null) {
      return null;
    }
    if (source.toString().startsWith("ProductTraceSource.")) {
      switch (source.toString()) {
        case "ProductTraceSource.UNSET":
          return ProductTraceSource.UNSET;
        case "ProductTraceSource.APM":
          return ProductTraceSource.APM;
        case "ProductTraceSource.ASM":
          return ProductTraceSource.ASM;
        case "ProductTraceSource.DSM":
          return ProductTraceSource.DSM;
        case "ProductTraceSource.DBM":
          return ProductTraceSource.DBM;
        default:
          throw new ArgumentConversionException("Cannot convert " + source);
      }
    }
    return source;
  }
}
