package com.datadog.iast.overhead;

import javax.annotation.Nullable;

public class Operations {

  private Operations() {}

  public static final Operation REPORT_VULNERABILITY =
      new Operation() {
        @Override
        public boolean hasQuota(@Nullable final OverheadContext context) {
          if (context == null) {
            return false;
          }
          return context.getAvailableQuota() > 0;
        }

        @Override
        public boolean consumeQuota(@Nullable final OverheadContext context) {
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
