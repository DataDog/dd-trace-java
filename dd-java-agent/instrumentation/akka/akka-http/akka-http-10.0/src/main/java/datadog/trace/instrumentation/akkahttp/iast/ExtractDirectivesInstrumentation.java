package datadog.trace.instrumentation.akkahttp.iast;

import static datadog.trace.instrumentation.akkahttp.iast.TraitMethodMatchers.isTraitDirectiveMethod;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.Uri;
import akka.http.scaladsl.server.Directive;
import akka.http.scaladsl.server.RequestContext;
import akka.http.scaladsl.server.RequestContextImpl;
import akka.http.scaladsl.server.directives.BasicDirectives$;
import akka.http.scaladsl.server.util.Tupler$;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.instrumentation.akkahttp.iast.helpers.TaintRequestContextFunction;
import datadog.trace.instrumentation.akkahttp.iast.helpers.TaintRequestFunction;
import datadog.trace.instrumentation.akkahttp.iast.helpers.TaintUriFunction;
import net.bytebuddy.asm.Advice;

/**
 * Wraps the directives created by calling the trait methods {@link BasicDirectives$#extractUri()},
 * {@link BasicDirectives$#extractRequest()} and {@link BasicDirectives$#extractRequestContext()}.
 * The wrapper taints the {@link Uri}, {@link HttpRequest} or {@link RequestContext}, respectively.
 *
 * @see MakeTaintableInstrumentation makes {@link Uri}, {@link HttpRequest} and {@link
 *     RequestContextImpl} taintable
 * @see UriInstrumentation instruments query methods on Uri to propagate taint to query strings
 * @see HttpRequestInstrumentation propagates taint from {@link HttpRequest} to the headers
 * @see RequestContextInstrumentation progapates taint from {@link RequestContextImpl} to {@link
 *     HttpRequest}
 * @see UnmarshallerInstrumentation propagates taint on unmarshalling of {@link HttpRequest}
 */
public class ExtractDirectivesInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "akka.http.scaladsl.server.directives.BasicDirectives$class",
      "akka.http.scaladsl.server.directives.BasicDirectives",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    instrumentDirective(transformer, "extractUri", "TaintUriDirectiveAdvice");
    instrumentDirective(transformer, "extractRequest", "TaintRequestDirectiveAdvice");
    instrumentDirective(transformer, "extractRequestContext", "TaintRequestContextDirectiveAdvice");
  }

  private void instrumentDirective(MethodTransformer transformation, String method, String advice) {
    transformation.applyAdvice(
        isTraitDirectiveMethod("akka.http.scaladsl.server.directives.BasicDirectives", method),
        ExtractDirectivesInstrumentation.class.getName() + '$' + advice);
  }

  static class TaintUriDirectiveAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_QUERY)
    static void after(@Advice.Return(readOnly = false) Directive directive) {
      directive = directive.tmap(TaintUriFunction.INSTANCE, Tupler$.MODULE$.forTuple(null));
    }
  }

  static class TaintRequestDirectiveAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_BODY)
    static void after(@Advice.Return(readOnly = false) Directive directive) {
      directive = directive.tmap(TaintRequestFunction.INSTANCE, Tupler$.MODULE$.forTuple(null));
    }
  }

  static class TaintRequestContextDirectiveAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_BODY)
    static void after(@Advice.Return(readOnly = false) Directive directive) {
      directive =
          directive.tmap(TaintRequestContextFunction.INSTANCE, Tupler$.MODULE$.forTuple(null));
    }
  }
}
