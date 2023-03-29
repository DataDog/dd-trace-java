package datadog.trace.instrumentation.servlet.http;

import datadog.trace.api.iast.source.WebModule;
import datadog.trace.util.stacktrace.StackUtils;
import java.lang.ref.WeakReference;
import java.util.Enumeration;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public abstract class TaintEnumerable<E> implements Enumeration<E> {
  private final WebModule webModule;
  private final WeakReference<Object> reqCtxRef;
  private final Enumeration<E> delegate;

  public TaintEnumerable(
      @Nonnull final WebModule webModule,
      @Nonnull final Object reqCtx,
      @Nonnull final Enumeration<E> delegate) {
    this.webModule = webModule;
    this.reqCtxRef = new WeakReference<>(reqCtx);
    this.delegate = delegate;
  }

  @Override
  public boolean hasMoreElements() {
    return delegate.hasMoreElements();
  }

  @Override
  public E nextElement() {
    E element = null;
    try {
      element = delegate.nextElement();
    } catch (RuntimeException e) {
      throw StackUtils.filterFirstDatadog(e);
    }
    if (!(element instanceof String)) {
      return element;
    }
    final Object reqCtx = reqCtxRef.get();
    if (reqCtx == null) {
      return element;
    }
    try {
      taint(webModule, reqCtx, (String) element);
    } catch (Throwable e) {
      webModule.onUnexpectedException("TaintEnumerable.nextElement threw", e);
    }
    return element;
  }

  protected abstract void taint(
      final @Nonnull WebModule module, final @Nonnull Object ctx, final @Nonnull String element);

  public static final class ParameterNamesEnumerable<E> extends TaintEnumerable<E> {
    public ParameterNamesEnumerable(
        @Nonnull final WebModule module,
        @Nonnull final Object ctx,
        @Nonnull final Enumeration<E> delegate) {
      super(module, ctx, delegate);
    }

    @Override
    protected void taint(@Nonnull WebModule module, @Nonnull Object ctx, @Nonnull String element) {
      module.onParameterName(element, ctx);
    }
  }

  public static final class HeaderValuesEnumerable<E> extends TaintEnumerable<E> {
    private final String name;

    public HeaderValuesEnumerable(
        @Nonnull final WebModule module,
        @Nonnull final Object ctx,
        @Nonnull final Enumeration<E> delegate,
        @Nullable final String name) {
      super(module, ctx, delegate);
      this.name = name;
    }

    @Override
    protected void taint(@Nonnull WebModule module, @Nonnull Object ctx, @Nonnull String element) {
      module.onHeaderValue(name, element, ctx);
    }
  }

  public static final class HeaderNamesEnumerable<E> extends TaintEnumerable<E> {

    public HeaderNamesEnumerable(
        @Nonnull final WebModule module,
        @Nonnull final Object ctx,
        @Nonnull final Enumeration<E> delegate) {
      super(module, ctx, delegate);
    }

    @Override
    protected void taint(@Nonnull WebModule module, @Nonnull Object ctx, @Nonnull String element) {
      module.onHeaderName(element, ctx);
    }
  }
}
