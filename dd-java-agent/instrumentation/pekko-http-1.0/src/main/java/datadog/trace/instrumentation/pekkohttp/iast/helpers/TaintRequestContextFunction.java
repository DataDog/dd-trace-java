package datadog.trace.instrumentation.pekkohttp.iast.helpers;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.apache.pekko.http.scaladsl.server.RequestContext;
import scala.Tuple1;
import scala.compat.java8.JFunction1;

public class TaintRequestContextFunction
    implements JFunction1<Tuple1<RequestContext>, Tuple1<RequestContext>> {
  public static final TaintRequestContextFunction INSTANCE = new TaintRequestContextFunction();

  @Override
  public Tuple1<RequestContext> apply(Tuple1<RequestContext> v1) {
    RequestContext reqCtx = v1._1();

    PropagationModule mod = InstrumentationBridge.PROPAGATION;
    if (mod == null) {
      return v1;
    }
    IastContext ctx = IastContext.Provider.get(AgentTracer.activeSpan());
    if (ctx == null) {
      return v1;
    }
    mod.taintObject(ctx, reqCtx, SourceTypes.REQUEST_BODY);

    return v1;
  }
}
