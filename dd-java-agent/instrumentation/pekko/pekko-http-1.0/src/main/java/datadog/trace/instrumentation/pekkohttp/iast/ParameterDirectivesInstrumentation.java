package datadog.trace.instrumentation.pekkohttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.instrumentation.pekkohttp.iast.helpers.TaintMapFunction;
import datadog.trace.instrumentation.pekkohttp.iast.helpers.TaintMultiMapFunction;
import datadog.trace.instrumentation.pekkohttp.iast.helpers.TaintSeqFunction;
import datadog.trace.instrumentation.pekkohttp.iast.helpers.TaintSingleParameterFunction;
import net.bytebuddy.asm.Advice;
import org.apache.pekko.http.scaladsl.server.Directive;
import org.apache.pekko.http.scaladsl.server.directives.ParameterDirectives;
import org.apache.pekko.http.scaladsl.server.util.Tupler$;

/**
 * Instruments the query parameter related directives. It works by wrapping the directives the
 * parameter is tainted before being passed the original directive.
 *
 * @see org.apache.pekko.http.scaladsl.server.directives.ParameterDirectives
 */
@AutoService(InstrumenterModule.class)
public class ParameterDirectivesInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  private static final String TRAIT_NAME =
      "org.apache.pekko.http.scaladsl.server.directives.ParameterDirectives";

  public ParameterDirectivesInstrumentation() {
    super("pekko-http");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      TRAIT_NAME + "$class", TRAIT_NAME,
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".helpers.ScalaToJava",
      packageName + ".helpers.TaintMultiMapFunction",
      packageName + ".helpers.TaintMapFunction",
      packageName + ".helpers.TaintSeqFunction",
      packageName + ".helpers.TaintSingleParameterFunction",
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
                takesArgument(
                    0,
                    named("org.apache.pekko.http.scaladsl.server.directives.ParameterDirectives")))
            .and(
                takesArgument(
                    1,
                    named(
                        "org.apache.pekko.http.scaladsl.server.directives.ParameterDirectives$ParamMagnet"))),
        ParameterDirectivesInstrumentation.class.getName()
            + "$TaintSingleParameterDirectiveOldScalaAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("parameter").or(named("parameters")))
            .and(
                returns(Object.class)
                    .or(returns(named("org.apache.pekko.http.scaladsl.server.Directive"))))
            .and(takesArguments(1))
            .and(
                takesArgument(
                    0,
                    named(
                        "org.apache.pekko.http.scaladsl.server.directives.ParameterDirectives$ParamMagnet"))),
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
