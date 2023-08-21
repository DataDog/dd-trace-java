package datadog.trace.instrumentation.akkahttp.iast.helpers;

import akka.http.scaladsl.model.headers.HttpCookiePair;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.source.WebModule;
import scala.Option;
import scala.Tuple1;
import scala.compat.java8.JFunction1;

public class TaintOptionalCookieFunction
    implements JFunction1<Tuple1<Option<HttpCookiePair>>, Tuple1<Option<HttpCookiePair>>> {
  public static final TaintOptionalCookieFunction INSTANCE = new TaintOptionalCookieFunction();

  @Override
  public Tuple1<Option<HttpCookiePair>> apply(Tuple1<Option<HttpCookiePair>> v1) {
    Option<HttpCookiePair> httpCookiePair = v1._1();

    WebModule mod = InstrumentationBridge.WEB;
    if (mod == null || httpCookiePair.isEmpty()) {
      return v1;
    }

    mod.onCookieValue(httpCookiePair.get().name(), httpCookiePair.get().value());
    return v1;
  }
}
