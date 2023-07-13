package datadog.trace.instrumentation.akkahttp.iast.helpers;

import akka.http.scaladsl.model.HttpRequest;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.Taintable;
import datadog.trace.api.iast.propagation.PropagationModule;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import scala.Tuple1;
import scala.compat.java8.JFunction1;

public class TaintRequestFunction implements JFunction1<Tuple1<HttpRequest>, Tuple1<HttpRequest>> {
  public static final TaintRequestFunction INSTANCE = new TaintRequestFunction();

  @Override
  @SuppressFBWarnings("BC_IMPOSSIBLE_INSTANCEOF")
  public Tuple1<HttpRequest> apply(Tuple1<HttpRequest> v1) {
    HttpRequest httpRequest = v1._1();

    PropagationModule mod = InstrumentationBridge.PROPAGATION;
    if (mod == null || !((Object) httpRequest instanceof Taintable)) {
      return v1;
    }
    mod.taintObject(SourceTypes.REQUEST_BODY, httpRequest);

    return v1;
  }
}
