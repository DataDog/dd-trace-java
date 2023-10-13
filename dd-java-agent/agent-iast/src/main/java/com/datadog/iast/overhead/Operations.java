package com.datadog.iast.overhead;

public class Operations {

  private Operations() {}

  public static final Operation REPORT_VULNERABILITY =
      new Operation() {
        @Override
        public boolean hasQuota(final OverheadContext context) {
          if (context == null) {
            return false;
          }
          return context.getAvailableQuota() > 0;
        }

        @Override
        public boolean consumeQuota(final OverheadContext context) {
          if (context == null) {
            return false;
          }
          return context.consumeQuota(1);
        }

        @Override
        public String toString() {
          return "Operation#REPORT_VULNERABILITY";
        }
      };
}
