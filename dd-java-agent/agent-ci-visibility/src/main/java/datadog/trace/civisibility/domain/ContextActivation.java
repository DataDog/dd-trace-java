package datadog.trace.civisibility.domain;

import datadog.context.Context;
import datadog.context.ContextHandle;

/** Keeps nested test and suite contexts restorable when they end out of order. */
final class ContextActivation {
  private static final ThreadLocal<ContextActivation> CURRENT = new ThreadLocal<>();

  private ContextActivation parent;
  private ContextHandle previousContext;
  private boolean ended;

  private ContextActivation(Context context) {
    parent = CURRENT.get();
    previousContext = context.swap();
    CURRENT.set(this);
  }

  static ContextActivation enter(Context context) {
    return new ContextActivation(context);
  }

  boolean isCurrent() {
    return CURRENT.get() == this;
  }

  void exit() {
    ended = true;
    if (!isCurrent()) {
      return;
    }

    ContextHandle restoreContext;
    ContextActivation activation = this;
    do {
      restoreContext = activation.previousContext;
      ContextActivation parent = activation.parent;
      activation.previousContext = null;
      activation.parent = null;
      activation = parent;
    } while (activation != null && activation.ended);

    if (activation == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(activation);
    }
    if (restoreContext != null) {
      restoreContext.swap();
    }
  }
}
