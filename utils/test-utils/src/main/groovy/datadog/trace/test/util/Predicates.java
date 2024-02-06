package datadog.trace.test.util;

import groovy.lang.Closure;
import java.math.BigDecimal;

public abstract class Predicates {

  private Predicates() {}

  public static final class IBM8 extends Closure<Boolean> {
    private static final String VENDOR_STRING = "IBM";
    private static final BigDecimal VERSION = new BigDecimal("1.8");

    public IBM8(Object owner, Object thisObject) {
      super(owner, thisObject);
    }

    @Override
    public Boolean call() {
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
