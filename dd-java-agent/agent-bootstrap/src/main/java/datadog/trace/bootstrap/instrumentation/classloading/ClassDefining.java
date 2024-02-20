package datadog.trace.bootstrap.instrumentation.classloading;

/** Provides a way for a single optional observer to be notified before a class is defined. */
public final class ClassDefining {
  private static volatile Observer OBSERVER = (loader, bytecode, offset, length) -> {};

  /** Registers the given observer to get notifications about class definitions. */
  public static void observe(Observer observer) {
    OBSERVER = observer;
  }

  /** Called from advice added to j.l.ClassLoader by DefineClassInstrumentation. */
  public static void begin(ClassLoader loader, byte[] bytecode, int offset, int length) {
    OBSERVER.beforeDefineClass(loader, bytecode, offset, length);
  }

  /** Observer of class definitions. */
  public interface Observer {
    void beforeDefineClass(ClassLoader loader, byte[] bytecode, int offset, int length);
  }
}
