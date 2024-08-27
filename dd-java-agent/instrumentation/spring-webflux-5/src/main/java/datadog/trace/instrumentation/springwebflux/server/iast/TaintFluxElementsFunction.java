package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.function.Function;

public class TaintFluxElementsFunction<T> implements Function<T, T> {

  final IastContext ctx;
  final PropagationModule propagation;

  public TaintFluxElementsFunction(IastContext ctx, PropagationModule propagationModule) {
    this.ctx = ctx;
    this.propagation = propagationModule;
  }

  @Override
  public T apply(T t) {
    propagation.taintObject(ctx, t, SourceTypes.REQUEST_BODY);
    return t;
  }
}
