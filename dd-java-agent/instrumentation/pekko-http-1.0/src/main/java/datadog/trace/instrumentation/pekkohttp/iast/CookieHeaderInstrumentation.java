package datadog.trace.instrumentation.pekkohttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import org.apache.pekko.http.javadsl.model.HttpHeader;
import org.apache.pekko.http.scaladsl.model.headers.Cookie;
import org.apache.pekko.http.scaladsl.model.headers.HttpCookiePair;
import scala.collection.Iterator;
import scala.collection.immutable.Seq;

/**
 * Propagates header taint when calling {@link Cookie#cookies()}.
 *
 * @see Cookie#getCookies() Java API. Is implemented by delegating to the instrumented method.
 */
@AutoService(Instrumenter.class)
public class CookieHeaderInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {
  public CookieHeaderInstrumentation() {
    super("pekko-http");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.pekko.http.scaladsl.model.headers.Cookie";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("cookies"))
            .and(returns(named("scala.collection.immutable.Seq")))
            .and(takesArguments(0)),
        CookieHeaderInstrumentation.class.getName() + "$TaintAllCookiesAdvice");
  }

  static class TaintAllCookiesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    static void after(
        @Advice.This HttpHeader cookie, @Advice.Return Seq<HttpCookiePair> cookiePairs) {
      PropagationModule prop = InstrumentationBridge.PROPAGATION;
      if (prop == null || cookiePairs == null || cookiePairs.isEmpty()) {
        return;
      }
      final IastContext ctx = IastContext.Provider.get();
      if (!prop.isTainted(ctx, cookie)) {
        return;
      }

      Iterator<HttpCookiePair> iterator = cookiePairs.iterator();
      while (iterator.hasNext()) {
        HttpCookiePair pair = iterator.next();
        final String name = pair.name(), value = pair.value();
        prop.taint(ctx, name, SourceTypes.REQUEST_COOKIE_NAME, name);
        prop.taint(ctx, value, SourceTypes.REQUEST_COOKIE_VALUE, name);
      }
    }
  }
}
