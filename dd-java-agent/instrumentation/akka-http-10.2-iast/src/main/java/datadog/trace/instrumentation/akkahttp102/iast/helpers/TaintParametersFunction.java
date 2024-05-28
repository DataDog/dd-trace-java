package datadog.trace.instrumentation.akkahttp102.iast.helpers;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import scala.Function1;
import scala.Option;
import scala.Tuple1;
import scala.collection.Iterable;
import scala.collection.Iterator;

public class TaintParametersFunction<T> implements Function1<Tuple1<T>, Tuple1<T>> {

  private final IastContext ctx;
  private final String paramName;

  public TaintParametersFunction(IastContext ctx, String paramName) {
    this.ctx = ctx;
    this.paramName = paramName;
  }

  @Override
  public Tuple1<T> apply(Tuple1<T> v1) {
    PropagationModule mod = InstrumentationBridge.PROPAGATION;
    if (mod == null) {
      return v1;
    }

    Object value = v1._1();
    if (value instanceof Option) {
      Option<?> option = (Option<?>) value;
      if (option.isEmpty()) {
        return v1;
      }
      value = option.get();
    }

    if (value instanceof Iterable) {
      Iterator<?> iterator = ((Iterable<?>) value).iterator();
      while (iterator.hasNext()) {
        Object o = iterator.next();
        if (o instanceof String) {
          mod.taintString(ctx, (String) o, SourceTypes.REQUEST_PARAMETER_VALUE, paramName);
        }
      }
    } else if (value instanceof String) {
      mod.taintString(ctx, (String) value, SourceTypes.REQUEST_PARAMETER_VALUE, paramName);
    }

    return v1;
  }
}
