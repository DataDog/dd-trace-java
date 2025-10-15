import java.util.concurrent.Callable;

public class LambdaGenerator {
  static Callable<?> wrapCallable(final Callable<?> callable) {
    return () -> callable.call();
  }

  static Runnable wrapRunnable(final Runnable runnable) {
    return () -> runnable.run();
  }
}
