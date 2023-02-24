package datadog.trace.instrumentation.iastinstrumenter.telemetry;

import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteSupplier;
import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.telemetry.Verbosity;
import java.util.Iterator;
import javax.annotation.Nonnull;

public class TelemetryCallSiteSupplier implements CallSiteSupplier {

  private final Verbosity verbosity;
  private final CallSiteSupplier delegate;

  public TelemetryCallSiteSupplier(final Verbosity verbosity, final CallSiteSupplier delegate) {
    this.verbosity = verbosity;
    this.delegate = delegate;
  }

  @Override
  public Iterable<CallSiteAdvice> get() {
    final Iterable<CallSiteAdvice> iterable = delegate.get();
    return () -> new IteratorAdapter(verbosity, iterable.iterator());
  }

  private static class IteratorAdapter implements Iterator<CallSiteAdvice> {

    private final Verbosity verbosity;
    private final Iterator<CallSiteAdvice> delegate;

    private IteratorAdapter(
        @Nonnull final Verbosity verbosity, @Nonnull final Iterator<CallSiteAdvice> delegate) {
      this.verbosity = verbosity;
      this.delegate = delegate;
    }

    @Override
    public boolean hasNext() {
      return delegate.hasNext();
    }

    @Override
    public CallSiteAdvice next() {
      CallSiteAdvice advice = delegate.next();
      if (advice instanceof IastAdvice.HasTelemetry) {
        final IastAdvice.HasTelemetry hasTelemetry = (IastAdvice.HasTelemetry) advice;
        hasTelemetry.enableTelemetry(includeRuntime(hasTelemetry.kind()));
      }
      return advice;
    }

    private boolean includeRuntime(final IastAdvice.Kind kind) {
      if (kind == IastAdvice.Kind.PROPAGATION) {
        return verbosity.isDebugEnabled();
      }
      return true;
    }
  }
}
