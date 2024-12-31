package datadog.context.propagation;

import datadog.context.Context;

class CompositePropagator implements Propagator {
  private final Propagator[] propagators;

  CompositePropagator(Propagator[] propagators) {
    this.propagators = propagators;
  }

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    for (Propagator propagator : this.propagators) {
      propagator.inject(context, carrier, setter);
    }
  }

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    for (Propagator propagator : this.propagators) {
      context = propagator.extract(context, carrier, visitor);
    }
    return context;
  }
}
