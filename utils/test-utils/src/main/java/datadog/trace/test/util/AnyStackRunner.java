package datadog.trace.test.util;

import net.bytebuddy.ByteBuddy;

/**
 * Utilities to call methods from callers with arbitrary class names.
 *
 * <p>This is useful to test functionality that depends on stack walking.
 */
public class AnyStackRunner {

  /**
   * Call the given runnable from a class with the given name.
   *
   * @param parentClass Caller class.
   * @param runnable Runnable to call.
   */
  public static void callWithinStack(final String parentClass, final Runnable runnable) {
    // Create a copy of the RunnerRunner template class with the given name.
    final Class<?> dynamicType =
        new ByteBuddy()
            .redefine(RunnerRunner.class)
            .name(parentClass)
            .make()
            .load(AnyStackRunner.class.getClassLoader())
            .getLoaded();
    try {
      Consumer<Runnable> obj =
          (Consumer<Runnable>) dynamicType.getDeclaredConstructor().newInstance();
      obj.accept(runnable);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException(ex);
    }
  }

  /** Public to ease code-generation. Not meant to be used out of the parent class. */
  public interface Consumer<T> {
    void accept(T arg);
  }

  /** Public to ease code-generation. Not meant to be used out of the parent class. */
  public static class RunnerRunner implements Consumer<Runnable> {
    @Override
    public void accept(final Runnable runnable) {
      runnable.run();
    }
  }
}
