package datadog.trace.bootstrap.config.provider;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class ConfigConverter {
  private static final ValueOfLookup LOOKUP = new ValueOfLookup();

  /**
   * @param value to parse by tClass::valueOf
   * @param tClass should contain static parsing method "T valueOf(String)"
   * @param <T>
   * @return value == null || value.trim().isEmpty() ? defaultValue : tClass.valueOf(value)
   * @throws NumberFormatException
   */
  static <T> T valueOf(final String value, @NonNull final Class<T> tClass) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    try {
      return (T) LOOKUP.get(tClass).invoke(value);
    } catch (final NumberFormatException e) {
      throw e;
    } catch (final Throwable e) {
      log.debug("Can't parse: ", e);
      throw new NumberFormatException(e.toString());
    }
  }

  private static class ValueOfLookup extends ClassValue<MethodHandle> {
    private static final MethodHandles.Lookup PUBLIC_LOOKUP = MethodHandles.publicLookup();

    @SneakyThrows
    @Override
    protected MethodHandle computeValue(Class<?> type) {
      try {
        return PUBLIC_LOOKUP.findStatic(type, "valueOf", MethodType.methodType(type, String.class));
      } catch (final NoSuchMethodException | IllegalAccessException e) {
        log.debug("Can't invoke or access 'valueOf': ", e);
        throw e;
      }
    }
  }
}
