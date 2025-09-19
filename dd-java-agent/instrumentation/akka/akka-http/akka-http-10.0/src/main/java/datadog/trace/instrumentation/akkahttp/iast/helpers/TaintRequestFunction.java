package datadog.trace.instrumentation.akkahttp.iast.helpers;

import akka.http.scaladsl.model.HttpRequest;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import scala.Tuple1;
import scala.compat.java8.JFunction1;

public class TaintRequestFunction implements JFunction1<Tuple1<HttpRequest>, Tuple1<HttpRequest>> {
  public static final TaintRequestFunction INSTANCE = new TaintRequestFunction();

  @Override
  public Tuple1<HttpRequest> apply(Tuple1<HttpRequest> v1) {
    HttpRequest httpRequest = v1._1();

    PropagationModule mod = InstrumentationBridge.PROPAGATION;
    if (mod == null || httpRequest == null) {
      return v1;
    }
    IastContext ctx = IastContext.Provider.get(AgentTracer.activeSpan());
    if (ctx == null) {
      return v1;
    }
    mod.taintObject(ctx, httpRequest, SourceTypes.REQUEST_BODY);

    return v1;
  }
}
