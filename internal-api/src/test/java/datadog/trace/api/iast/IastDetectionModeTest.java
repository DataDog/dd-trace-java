package datadog.trace.api.iast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.ConfigDefaults;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class IastDetectionModeTest {

  @TableTest({
    "scenario | mode    | expected                            ",
    "FULL     | FULL    | UNLIMITED                           ",
    "DEFAULT  | DEFAULT | DEFAULT_IAST_MAX_CONCURRENT_REQUESTS"
  })
  void testMaxConcurrentRequests(
      IastDetectionMode mode, @ConvertWith(ConstantConverter.class) int expected) {
    ConfigProvider config = ConfigProvider.getInstance();

    int result = mode.getIastMaxConcurrentRequests(config);

    assertEquals(expected, result);
  }

  @TableTest({
    "scenario | mode    | expected                                ",
    "FULL     | FULL    | UNLIMITED                               ",
    "DEFAULT  | DEFAULT | DEFAULT_IAST_VULNERABILITIES_PER_REQUEST"
  })
  void testVulnerabilitiesPerRequests(
      IastDetectionMode mode, @ConvertWith(ConstantConverter.class) int expected) {
    ConfigProvider config = ConfigProvider.getInstance();

    int result = mode.getIastVulnerabilitiesPerRequest(config);

    assertEquals(expected, result);
  }

  @TableTest({
    "scenario | mode    | expected                     ",
    "FULL     | FULL    | 100                          ",
    "DEFAULT  | DEFAULT | DEFAULT_IAST_REQUEST_SAMPLING"
  })
  void testSampling(IastDetectionMode mode, @ConvertWith(ConstantConverter.class) float expected) {
    ConfigProvider config = ConfigProvider.getInstance();

    float result = mode.getIastRequestSampling(config);

    assertEquals(expected, result);
  }

  @TableTest({
    "scenario | mode    | expected                          ",
    "FULL     | FULL    | false                             ",
    "DEFAULT  | DEFAULT | DEFAULT_IAST_DEDUPLICATION_ENABLED"
  })
  void testDeduplication(
      IastDetectionMode mode, @ConvertWith(ConstantConverter.class) boolean expected) {
    ConfigProvider config = ConfigProvider.getInstance();

    boolean result = mode.isIastDeduplicationEnabled(config);

    assertEquals(expected, result);
  }

  /**
   * Resolves {@link ConfigDefaults} and {@link IastDetectionMode} constant names, or parses
   * literals, into the target parameter type.
   */
  static final class ConstantConverter implements ArgumentConverter {
    private static final Class<?>[] CLASSES = {ConfigDefaults.class, IastDetectionMode.class};

    @Override
    public Object convert(Object source, ParameterContext context) {
      String value = (String) source;
      Class<?> type = context.getParameter().getType();
      for (Class<?> clazz : CLASSES) {
        try {
          Object constant = clazz.getField(value).get(null);
          return type == float.class ? ((Number) constant).floatValue() : constant;
        } catch (NoSuchFieldException ignored) {
          // try the next class
        } catch (IllegalAccessException e) {
          throw new ArgumentConversionException("Cannot read constant " + value, e);
        }
      }
      if (type == boolean.class) {
        return Boolean.parseBoolean(value);
      }
      if (type == float.class) {
        return Float.parseFloat(value);
      }
      return Integer.parseInt(value);
    }
  }
}
