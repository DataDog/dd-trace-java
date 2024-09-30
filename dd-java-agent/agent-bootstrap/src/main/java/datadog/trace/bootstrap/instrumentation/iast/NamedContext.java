package datadog.trace.bootstrap.instrumentation.iast;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Taintable.Source;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.bootstrap.ContextStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Provides a context to store the current name in order to taint values in parsing operations (e.g.
 * JSON parsing)
 */
public abstract class NamedContext {

  public abstract void taintValue(@Nullable String value);

  public abstract void taintName(@Nullable String name);

  @Nonnull
  public static <E> NamedContext getOrCreate(
      @Nonnull final ContextStore<E, NamedContext> store, @Nonnull final E target) {
    NamedContext result = store.get(target);
    if (result != null) {
      return result;
    }
    final PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      final Source source = module.findSource(target);
      if (source != null) {
        result = new NamedContextImpl(module, source);
      }
    }
    result = result == null ? NoOp.INSTANCE : result;
    store.put(target, result);
    return result;
  }

  private static class NoOp extends NamedContext {

    private static final NamedContext INSTANCE = new NoOp();

    @Override
    public void taintValue(@Nullable final String value) {}

    @Override
    public void taintName(@Nullable final String name) {}
  }

  private static class NamedContextImpl extends NamedContext {
    @Nonnull private final PropagationModule module;
    @Nonnull private final Source source;
    @Nullable private String currentName;

    private boolean fetched;
    @Nullable private IastContext context;

    public NamedContextImpl(@Nonnull final PropagationModule module, @Nonnull final Source source) {
      this.module = module;
      this.source = source;
    }

    @Override
    public void taintValue(@Nullable final String value) {
      module.taintString(iastCtx(), value, source.getOrigin(), currentName, source.getValue());
    }

    @Override
    @SuppressWarnings("StringEquality")
    @SuppressFBWarnings("ES_COMPARING_PARAMETER_STRING_WITH_EQ")
    public void taintName(@Nullable final String name) {
      // prevent tainting the same name more than once
      if (currentName != name) {
        currentName = name;
        module.taintString(iastCtx(), name, source.getOrigin(), name, source.getValue());
      }
    }

    private IastContext iastCtx() {
      if (!fetched) {
        fetched = true;
        context = IastContext.Provider.get();
      }
      return context;
    }
  }
}
