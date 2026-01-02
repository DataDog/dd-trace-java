package datadog.trace.instrumentation.akkahttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.server.Directive;
import akka.http.scaladsl.server.directives.ParameterDirectives;
import akka.http.scaladsl.server.util.Tupler$;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.instrumentation.akkahttp.iast.helpers.TaintMapFunction;
import datadog.trace.instrumentation.akkahttp.iast.helpers.TaintMultiMapFunction;
import datadog.trace.instrumentation.akkahttp.iast.helpers.TaintSeqFunction;
import datadog.trace.instrumentation.akkahttp.iast.helpers.TaintSingleParameterFunction;
import net.bytebuddy.asm.Advice;

/**
 * Instruments the query parameter related directives. It works by wrapping the directives the
 * parameter is tainted before being passed the original directive.
 *
 * @see akka.http.scaladsl.server.directives.ParameterDirectives
 */
public class ParameterDirectivesInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  private static final String TRAIT_NAME =
      "akka.http.scaladsl.server.directives.ParameterDirectives";

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      TRAIT_NAME + "$class", TRAIT_NAME,
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // the Java API delegates to the Scala API
    transformDirective(transformer, "parameterMultiMap", "TaintMultiMapDirectiveAdvice");
    transformDirective(transformer, "parameterMap", "TaintMapDirectiveAdvice");
    transformDirective(transformer, "parameterSeq", "TaintSeqDirectiveAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("parameter").or(named("parameters")))
            .and(returns(Object.class))
            .and(takesArguments(2))
            .and(
                takesArgument(0, named("akka.http.scaladsl.server.directives.ParameterDirectives")))
            .and(
                takesArgument(
                    1,
                    named("akka.http.scaladsl.server.directives.ParameterDirectives$ParamMagnet"))),
        ParameterDirectivesInstrumentation.class.getName()
            + "$TaintSingleParameterDirectiveOldScalaAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("parameter").or(named("parameters")))
            .and(returns(Object.class).or(returns(named("akka.http.scaladsl.server.Directive"))))
            .and(takesArguments(1))
            .and(
                takesArgument(
                    0,
                    named("akka.http.scaladsl.server.directives.ParameterDirectives$ParamMagnet"))),
        ParameterDirectivesInstrumentation.class.getName()
            + "$TaintSingleParameterDirectiveNewScalaAdvice");
  }

  private void transformDirective(
      MethodTransformer transformation, String methodName, String adviceClass) {
    transformation.applyAdvice(
        TraitMethodMatchers.isTraitDirectiveMethod(TRAIT_NAME, methodName),
        ParameterDirectivesInstrumentation.class.getName() + "$" + adviceClass);
  }

  static class TaintMultiMapDirectiveAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    static void after(@Advice.Return(readOnly = false) Directive directive) {
      directive = directive.tmap(TaintMultiMapFunction.INSTANCE, Tupler$.MODULE$.forTuple(null));
    }
  }

  static class TaintMapDirectiveAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    static void after(@Advice.Return(readOnly = false) Directive directive) {
      directive = directive.tmap(TaintMapFunction.INSTANCE, Tupler$.MODULE$.forTuple(null));
    }
  }

  static class TaintSeqDirectiveAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    static void after(@Advice.Return(readOnly = false) Directive directive) {
      directive = directive.tmap(TaintSeqFunction.INSTANCE, Tupler$.MODULE$.forTuple(null));
    }
  }

  static class TaintSingleParameterDirectiveOldScalaAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    static void after(
        @Advice.Return(readOnly = false) Object retval,
        @Advice.Argument(1) ParameterDirectives.ParamMagnet pmag) {
      if (!(retval instanceof Directive)) {
        return;
      }

      try {
        retval =
            ((Directive) retval)
                .tmap(new TaintSingleParameterFunction<>(pmag), Tupler$.MODULE$.forTuple(null));
      } catch (Exception e) {
        throw new RuntimeException(e); // propagate so it's logged
      }
    }
  }

  static class TaintSingleParameterDirectiveNewScalaAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    static void after(
        @Advice.Return(readOnly = false) Object retval,
        @Advice.Argument(0) ParameterDirectives.ParamMagnet pmag) {
      if (!(retval instanceof Directive)) {
        return;
      }

      try {
        retval =
            ((Directive) retval)
                .tmap(new TaintSingleParameterFunction<>(pmag), Tupler$.MODULE$.forTuple(null));
      } catch (Exception e) {
        throw new RuntimeException(e); // propagate so it's logged
      }
    }
  }
}
