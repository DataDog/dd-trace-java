package datadog.trace.test.util;

import datadog.environment.JavaVirtualMachine;
import java.util.function.Predicate;

public abstract class Predicates {

  private Predicates() {}

  public static class IBM implements Predicate<String> {
    private static final String IBM_VENDOR_STRING = "IBM";

    @Override
    public boolean test(String s) {
      return JavaVirtualMachine.getRuntimeVendor().contains(IBM_VENDOR_STRING);
    }
  }

  public static final class IBM8 extends IBM {

    @Override
    public boolean test(final String spec) {
      if (!super.test(spec)) {
        return false;
      }
      return JavaVirtualMachine.isJavaVersion(8);
    }
  }

  public static class ORACLE implements Predicate<String> {
    private static final String ORACLE_VENDOR_STRING = "Oracle";

    @Override
    public boolean test(String s) {
      return JavaVirtualMachine.getRuntimeVendor().contains(ORACLE_VENDOR_STRING);
    }
  }

  public static final class ORACLE8 extends ORACLE {

    @Override
    public boolean test(final String spec) {
      if (!super.test(spec)) {
        return false;
      }
      return JavaVirtualMachine.isJavaVersion(8);
    }
  }
}
