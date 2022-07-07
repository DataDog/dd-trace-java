package datadog.trace.bootstrap.debugger.el;

public final class Values {
  /** A generic undefined value object */
  public static final Object UNDEFINED_OBJECT =
      new Object() {
        @Override
        public String toString() {
          return "UNDEFINED";
        }
      };

  public static final Object NULL_OBJECT =
      new Object() {
        @Override
        public String toString() {
          return "NULL";
        }
      };
}
