package datadog.trace.instrumentation.pekkohttp.iast;

import static datadog.trace.instrumentation.pekkohttp.iast.TraitMethodMatchers.isTraitDirectiveMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.instrumentation.pekkohttp.iast.helpers.TaintRequestContextFunction;
import datadog.trace.instrumentation.pekkohttp.iast.helpers.TaintRequestFunction;
import datadog.trace.instrumentation.pekkohttp.iast.helpers.TaintUriFunction;
import net.bytebuddy.asm.Advice;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.Uri;
import org.apache.pekko.http.scaladsl.server.Directive;
import org.apache.pekko.http.scaladsl.server.RequestContext;
import org.apache.pekko.http.scaladsl.server.RequestContextImpl;
import org.apache.pekko.http.scaladsl.server.directives.BasicDirectives$;
import org.apache.pekko.http.scaladsl.server.util.Tupler$;

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
@AutoService(InstrumenterModule.class)
public class ExtractDirectivesInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  public ExtractDirectivesInstrumentation() {
    super("pekko-http");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.pekko.http.scaladsl.server.directives.BasicDirectives$class",
      "org.apache.pekko.http.scaladsl.server.directives.BasicDirectives",
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".helpers.TaintUriFunction",
      packageName + ".helpers.TaintRequestFunction",
      packageName + ".helpers.TaintRequestContextFunction",
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
        isTraitDirectiveMethod(
            "org.apache.pekko.http.scaladsl.server.directives.BasicDirectives", method),
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
