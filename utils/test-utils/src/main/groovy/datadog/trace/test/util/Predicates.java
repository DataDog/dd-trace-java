package datadog.trace.test.util;

import java.math.BigDecimal;
import java.util.function.Predicate;

public abstract class Predicates {

  private Predicates() {}

  public static class IBM implements Predicate<String> {
    private static final String IBM_VENDOR_STRING = "IBM";

    @Override
    public boolean test(String s) {
      return System.getProperty("java.vendor", "").contains(IBM_VENDOR_STRING);
    }
  }

  public static final class IBM8 extends IBM {
    private static final BigDecimal VERSION = new BigDecimal("1.8");

    @Override
    public boolean test(final String spec) {
      if (!super.test(spec)) {
        return false;
      }
      final BigDecimal version =
          new BigDecimal(System.getProperty("java.specification.version", "-1"));
      return version.equals(VERSION);
    }
  }

  public static class ORACLE implements Predicate<String> {
    private static final String ORACLE_VENDOR_STRING = "Oracle";

    @Override
    public boolean test(String s) {
      return System.getProperty("java.vendor", "").contains(ORACLE_VENDOR_STRING);
    }
  }

  public static final class ORACLE8 extends ORACLE {
    private static final BigDecimal VERSION = new BigDecimal("1.8");

    @Override
    public boolean test(final String spec) {
      if (!super.test(spec)) {
        return false;
      }
      final BigDecimal version =
          new BigDecimal(System.getProperty("java.specification.version", "-1"));
      return version.equals(VERSION);
    }
  }
}
