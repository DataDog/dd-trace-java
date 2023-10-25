package datadog.trace.instrumentation.pekkohttp.iast.helpers;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import org.apache.pekko.http.scaladsl.model.headers.HttpCookiePair;
import scala.Tuple1;
import scala.compat.java8.JFunction1;

public class TaintCookieFunction
    implements JFunction1<Tuple1<HttpCookiePair>, Tuple1<HttpCookiePair>> {
  public static final TaintCookieFunction INSTANCE = new TaintCookieFunction();

  @Override
  public Tuple1<HttpCookiePair> apply(Tuple1<HttpCookiePair> v1) {
    HttpCookiePair httpCookiePair = v1._1();

    PropagationModule mod = InstrumentationBridge.PROPAGATION;
    if (mod == null) {
      return v1;
    }
    mod.taint(SourceTypes.REQUEST_COOKIE_VALUE, httpCookiePair.name(), httpCookiePair.value());
    return v1;
  }
}
