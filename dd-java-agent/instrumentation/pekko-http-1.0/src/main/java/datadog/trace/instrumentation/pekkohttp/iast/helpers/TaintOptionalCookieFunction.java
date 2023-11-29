package datadog.trace.instrumentation.pekkohttp.iast.helpers;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import org.apache.pekko.http.scaladsl.model.headers.HttpCookiePair;
import scala.Option;
import scala.Tuple1;
import scala.compat.java8.JFunction1;

public class TaintOptionalCookieFunction
    implements JFunction1<Tuple1<Option<HttpCookiePair>>, Tuple1<Option<HttpCookiePair>>> {
  public static final TaintOptionalCookieFunction INSTANCE = new TaintOptionalCookieFunction();

  @Override
  public Tuple1<Option<HttpCookiePair>> apply(Tuple1<Option<HttpCookiePair>> v1) {
    Option<HttpCookiePair> httpCookiePair = v1._1();

    PropagationModule mod = InstrumentationBridge.PROPAGATION;
    if (mod == null || httpCookiePair.isEmpty()) {
      return v1;
    }
    final HttpCookiePair cookie = httpCookiePair.get();
    final String name = cookie.name();
    final String value = cookie.value();
    mod.taint(name, SourceTypes.REQUEST_COOKIE_NAME, name);
    mod.taint(value, SourceTypes.REQUEST_COOKIE_VALUE, name);
    return v1;
  }
}
