package datadog.trace.core.propagation.opg;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.propagation.HttpCodec;

/**
 * Decorates an {@link HttpCodec.Extractor} so that, after the underlying codec extracts a context,
 * it is run through the {@link OrgGuardEnforcer} which may strip the Datadog-side context when the
 * inbound Org Propagation Marker (OPM) does not match the local one.
 */
final class OrgGuardEnforcingExtractor implements HttpCodec.Extractor {

  private final HttpCodec.Extractor delegate;
  private final OrgGuardEnforcer enforcer;

  OrgGuardEnforcingExtractor(HttpCodec.Extractor delegate, OrgGuardEnforcer enforcer) {
    this.delegate = delegate;
    this.enforcer = enforcer;
  }

  @Override
  public <C> TagContext extract(C carrier, AgentPropagation.ContextVisitor<C> getter) {
    return enforcer.enforce(delegate.extract(carrier, getter));
  }

  @Override
  public void cleanup() {
    delegate.cleanup();
  }
}
