package com.datadog.iast.overhead;

public class Operations {

  private Operations() {}

  public static final Operation REPORT_VULNERABILITY =
      new Operation() {
        @Override
        public boolean hasQuota(OverheadContext context) {
          if (context == null) {
            return false;
          }
          return context.getAvailableQuota() > 0;
        }

        @Override
        public boolean consumeQuota(OverheadContext context) {
          if (context == null) {
            return false;
          }
          return context.consumeQuota(1);
        }
      };
}
