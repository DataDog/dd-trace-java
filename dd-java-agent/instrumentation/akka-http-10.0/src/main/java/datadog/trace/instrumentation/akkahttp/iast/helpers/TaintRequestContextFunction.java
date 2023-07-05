package datadog.trace.instrumentation.akkahttp.iast.helpers;

import akka.http.scaladsl.server.RequestContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.Taintable;
import datadog.trace.api.iast.propagation.PropagationModule;
import scala.Tuple1;
import scala.compat.java8.JFunction1;

public class TaintRequestContextFunction
    implements JFunction1<Tuple1<RequestContext>, Tuple1<RequestContext>> {
  public static final TaintRequestContextFunction INSTANCE = new TaintRequestContextFunction();

  @Override
  public Tuple1<RequestContext> apply(Tuple1<RequestContext> v1) {
    RequestContext reqCtx = v1._1();

    PropagationModule mod = InstrumentationBridge.PROPAGATION;
    if (mod == null || !(reqCtx instanceof Taintable)) {
      return v1;
    }
    mod.taintObject(SourceTypes.REQUEST_BODY, reqCtx);

    return v1;
  }
}
