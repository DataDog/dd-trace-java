package datadog.context.propagation;

import datadog.context.Context;

final class NoopPropagator implements Propagator {
  static final NoopPropagator INSTANCE = new NoopPropagator();

  private NoopPropagator() {}

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    // noop
  }

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    return context;
  }
}
