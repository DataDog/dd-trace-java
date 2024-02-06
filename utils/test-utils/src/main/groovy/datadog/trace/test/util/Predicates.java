package datadog.trace.test.util;

import java.math.BigDecimal;
import java.util.function.Predicate;

public abstract class Predicates {

  private Predicates() {}

  public static final class IBM8 implements Predicate<String> {
    private static final String VENDOR_STRING = "IBM";
    private static final BigDecimal VERSION = new BigDecimal("1.8");

    @Override
    public boolean test(final String spec) {
      final String vendor = System.getProperty("java.vendor", "");
      if (!vendor.contains(VENDOR_STRING)) {
        return false;
      }
      final BigDecimal version =
          new BigDecimal(System.getProperty("java.specification.version", "-1"));
      return version.equals(VERSION);
    }
  }
}
