package datadog.trace.agent.test;

import java.util.function.Supplier;

public class AnonymousClass {

  static {
    String id =
        new Supplier<String>() {
          @Override
          public String get() {
            return Long.toString(System.currentTimeMillis());
          }
        }.get();
    assert id != null;
  }

  @Override
  public String toString() {
    return new Supplier<String>() {
      @Override
      public String get() {
        return AnonymousClass.class + "@" + System.identityHashCode(this);
      }
    }.get();
  }
}
