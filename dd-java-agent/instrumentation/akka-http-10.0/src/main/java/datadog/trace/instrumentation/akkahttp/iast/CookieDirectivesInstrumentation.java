package datadog.trace.instrumentation.akkahttp.iast;

import static datadog.trace.instrumentation.akkahttp.iast.TraitMethodMatchers.isTraitDirectiveMethod;

import akka.http.scaladsl.server.Directive;
import akka.http.scaladsl.server.util.Tupler$;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.instrumentation.akkahttp.iast.helpers.TaintCookieFunction;
import datadog.trace.instrumentation.akkahttp.iast.helpers.TaintOptionalCookieFunction;
import net.bytebuddy.asm.Advice;

/**
 * Instruments the cookie directives by wrapping the returned directive so that when a cookie is
 * passed it is tainted just before being handed to the original directive.
 *
 * <p>These directives are used when fetching a specific cookie by name. For tainting when fetching
 * all the cookies, see {@link CookieHeaderInstrumentation}.
 */
@AutoService(Instrumenter.class)
public class CookieDirectivesInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForKnownTypes {
  public CookieDirectivesInstrumentation() {
    super("akka-http");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "akka.http.scaladsl.server.directives.CookieDirectives$class", // scala 2.11
      "akka.http.scaladsl.server.directives.CookieDirectives", // scala 2.12+ (default methods)
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".helpers.TaintCookieFunction",
      packageName + ".helpers.TaintOptionalCookieFunction",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    String traitName = "akka.http.scaladsl.server.directives.CookieDirectives";
    transformation.applyAdvice(
        isTraitDirectiveMethod(traitName, "cookie", "java.lang.String"),
        CookieDirectivesInstrumentation.class.getName() + "$TaintCookieAdvice");
    transformation.applyAdvice(
        isTraitDirectiveMethod(traitName, "optionalCookie", "java.lang.String"),
        CookieDirectivesInstrumentation.class.getName() + "$TaintOptionalCookieAdvice");
  }

  static class TaintCookieAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    static void after(@Advice.Return(readOnly = false) Directive directive) {
      directive = directive.tmap(TaintCookieFunction.INSTANCE, Tupler$.MODULE$.forTuple(null));
    }
  }

  static class TaintOptionalCookieAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    static void after(@Advice.Return(readOnly = false) Directive directive) {
      directive =
          directive.tmap(TaintOptionalCookieFunction.INSTANCE, Tupler$.MODULE$.forTuple(null));
    }
  }
}
