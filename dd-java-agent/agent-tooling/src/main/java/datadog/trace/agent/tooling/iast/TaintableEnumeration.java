package datadog.trace.agent.tooling.iast;

import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import datadog.trace.util.stacktrace.StackUtils;
import java.util.Enumeration;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class TaintableEnumeration implements Enumeration<String> {

  private static final String CLASS_NAME = TaintableEnumeration.class.getName();

  private final TaintedObjects to;

  private final PropagationModule module;

  private final byte origin;

  private final CharSequence name;

  private final boolean useValueAsName;

  private final Enumeration<String> delegate;

  private TaintableEnumeration(
      @Nullable final TaintedObjects to,
      @Nonnull final Enumeration<String> delegate,
      @Nonnull final PropagationModule module,
      final byte origin,
      @Nullable final CharSequence name,
      final boolean useValueAsName) {
    this.to = to;
    this.delegate = delegate;
    this.module = module;
    this.origin = origin;
    this.name = name;
    this.useValueAsName = useValueAsName;
  }

  @Override
  public boolean hasMoreElements() {
    try {
      return delegate.hasMoreElements();
    } catch (Throwable e) {
      StackUtils.filterFirst(e, TaintableEnumeration::nonTaintableEnumerationStack);
      throw e;
    }
  }

  @Override
  public String nextElement() {
    final String next;
    try {
      next = delegate.nextElement();
    } catch (Throwable e) {
      StackUtils.filterFirst(e, TaintableEnumeration::nonTaintableEnumerationStack);
      throw e;
    }
    try {
      module.taintObject(to, next, origin, name(next));
    } catch (final Throwable e) {
      module.onUnexpectedException("Failed to taint enumeration", e);
    }
    return next;
  }

  private CharSequence name(final String value) {
    if (name != null) {
      return name;
    }
    return useValueAsName ? value : null;
  }

  private static boolean nonTaintableEnumerationStack(final StackTraceElement element) {
    return !CLASS_NAME.equals(element.getClassName());
  }

  public static Enumeration<String> wrap(
      @Nullable final TaintedObjects to,
      @Nonnull final Enumeration<String> delegate,
      @Nonnull final PropagationModule module,
      final byte origin,
      @Nullable final CharSequence name) {
    return new TaintableEnumeration(to, delegate, module, origin, name, false);
  }

  public static Enumeration<String> wrap(
      @Nullable final TaintedObjects to,
      @Nonnull final Enumeration<String> delegate,
      @Nonnull final PropagationModule module,
      final byte origin,
      boolean useValueAsName) {
    return new TaintableEnumeration(to, delegate, module, origin, null, useValueAsName);
  }
}
