package datadog.trace.agent.test;

import java.util.function.Supplier;

public class EnclosedClasses {

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
        return EnclosedClasses.class + "@" + System.identityHashCode(this);
      }
    }.get();
  }

  public static class InnerStatic {
    @Override
    public String toString() {
      return new Supplier<String>() {
        @Override
        public String get() {
          return InnerStatic.class + "@" + System.identityHashCode(this);
        }
      }.get();
    }
  }

  public static class Inner {
    @Override
    public String toString() {
      return new Supplier<String>() {
        @Override
        public String get() {
          return Inner.class + "@" + System.identityHashCode(this);
        }
      }.get();
    }
  }

  public interface Interface {}

  public abstract static class Abstract {}

  public @interface Annotation {}

  public enum Enum {}
}
