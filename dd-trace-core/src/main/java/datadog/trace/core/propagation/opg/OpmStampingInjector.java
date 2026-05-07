package datadog.trace.core.propagation.opg;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.HttpCodec;
import java.util.function.Supplier;

/**
 * Decorates an {@link HttpCodec.Injector} so that, just before delegating to the underlying codecs,
 * it stamps the local Org Propagation Marker (OPM) onto the span's propagation tags. The codecs
 * (Datadog, W3C tracecontext) then serialize whatever is in the propagation tags, which causes the
 * local OPM to overwrite any inbound OPM in {@code _dd.p.opm} / {@code t.opm}.
 *
 * <p>If the supplier returns {@code null} (the agent hasn't reported an OPM yet), this is a no-op
 * and any inbound OPM is forwarded as-is, per the RFC.
 */
final class OpmStampingInjector implements HttpCodec.Injector {

  private final HttpCodec.Injector delegate;
  private final Supplier<String> localOpmSupplier;

  OpmStampingInjector(HttpCodec.Injector delegate, Supplier<String> localOpmSupplier) {
    this.delegate = delegate;
    this.localOpmSupplier = localOpmSupplier;
  }

  @Override
  public <C> void inject(DDSpanContext context, C carrier, CarrierSetter<C> setter) {
    String localOpm = localOpmSupplier.get();
    if (localOpm != null) {
      context.getPropagationTags().updateOrgPropagationMarker(localOpm);
    }
    delegate.inject(context, carrier, setter);
  }
}
