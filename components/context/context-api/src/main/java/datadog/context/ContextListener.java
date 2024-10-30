package datadog.context;

@FunctionalInterface
public interface ContextListener {

  void onAttached(Context previous, Context currentContext);

  default void onAttachedFromNewScope(Context previous, Context current, ContextScope scope) {
    onAttached(previous, current);
  }

  default void onAttachedFromCloseScope(Context previous, Context current, ContextScope scope) {
    onAttached(previous, current);
  }
}
