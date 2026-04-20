package datadog.trace.instrumentation.iastinstrumenter.telemetry;

import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteSupplier;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.api.iast.IastCallSites;
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
  public Iterable<CallSites> get() {
    final Iterable<CallSites> iterable = delegate.get();
    return () -> new IteratorAdapter(verbosity, iterable.iterator());
  }

  private static class IteratorAdapter implements Iterator<CallSites> {

    private final Verbosity verbosity;
    private final Iterator<CallSites> delegate;

    private IteratorAdapter(
        @Nonnull final Verbosity verbosity, @Nonnull final Iterator<CallSites> delegate) {
      this.verbosity = verbosity;
      this.delegate = delegate;
    }

    @Override
    public boolean hasNext() {
      return delegate.hasNext();
    }

    @Override
    public CallSites next() {
      CallSites advice = delegate.next();
      if (advice instanceof IastCallSites.HasTelemetry) {
        final IastCallSites.HasTelemetry hasTelemetry = (IastCallSites.HasTelemetry) advice;
        hasTelemetry.setVerbosity(verbosity);
      }
      return advice;
    }
  }
}
