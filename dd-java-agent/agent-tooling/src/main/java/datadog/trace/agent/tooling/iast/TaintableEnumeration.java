package datadog.trace.agent.tooling.iast;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.util.stacktrace.StackUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Enumeration;
import javax.annotation.Nullable;

public class TaintableEnumeration implements Enumeration<String> {

  private static final String CLASS_NAME = TaintableEnumeration.class.getName();

  private final IastContext context;

  private final PropagationModule module;

  private final byte origin;

  private final CharSequence name;

  private final boolean useValueAsName;

  private final Enumeration<String> delegate;

  private TaintableEnumeration(
      final IastContext ctx,
      @NonNull final Enumeration<String> delegate,
      @NonNull final PropagationModule module,
      final byte origin,
      @Nullable final CharSequence name,
      final boolean useValueAsName) {
    this.context = ctx;
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
      module.taintString(context, next, origin, name(next));
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
      final IastContext ctx,
      @NonNull final Enumeration<String> delegate,
      @NonNull final PropagationModule module,
      final byte origin,
      @Nullable final CharSequence name) {
    return new TaintableEnumeration(ctx, delegate, module, origin, name, false);
  }

  public static Enumeration<String> wrap(
      final IastContext ctx,
      @NonNull final Enumeration<String> delegate,
      @NonNull final PropagationModule module,
      final byte origin,
      boolean useValueAsName) {
    return new TaintableEnumeration(ctx, delegate, module, origin, null, useValueAsName);
  }
}
