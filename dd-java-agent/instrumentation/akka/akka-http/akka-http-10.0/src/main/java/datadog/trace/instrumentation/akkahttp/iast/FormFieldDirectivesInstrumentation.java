package datadog.trace.instrumentation.akkahttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.akkahttp.iast.TraitMethodMatchers.isTraitDirectiveMethod;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.server.Directive;
import akka.http.scaladsl.server.directives.FormFieldDirectives;
import akka.http.scaladsl.server.util.Tupler$;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.instrumentation.akkahttp.iast.helpers.TaintSingleParameterFunction;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Instruments the from field related directives. These work both for urlencoded and multipart
 * entities.
 *
 * @see FormFieldDirectives
 * @see ParameterDirectivesInstrumentation with which most of the implementation is shared
 */
public class FormFieldDirectivesInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  private static final String TRAIT_CLASS =
      "akka.http.scaladsl.server.directives.FormFieldDirectives";

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      TRAIT_CLASS + "$class", TRAIT_CLASS,
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // the Java API delegates to the Scala API
    transformDirective(transformer, "formFieldMultiMap", "TaintMultiMapDirectiveAdvice");
    transformDirective(transformer, "formFieldMap", "TaintMapDirectiveAdvice");
    transformDirective(transformer, "formFieldSeq", "TaintSeqDirectiveAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("formField").or(named("formFields")))
            .and(returns(Object.class))
            .and(takesArguments(2))
            .and(
                takesArgument(0, named("akka.http.scaladsl.server.directives.FormFieldDirectives")))
            .and(
                takesArgument(
                    1,
                    named("akka.http.scaladsl.server.directives.FormFieldDirectives$FieldMagnet"))),
        FormFieldDirectivesInstrumentation.class.getName()
            + "$TaintSingleFormFieldDirectiveOldScalaAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("formField").or(named("formFields")))
            .and(returns(Object.class).or(returns(named("akka.http.scaladsl.server.Directive"))))
            .and(takesArguments(1))
            .and(
                takesArgument(
                    0,
                    named("akka.http.scaladsl.server.directives.FormFieldDirectives$FieldMagnet"))),
        FormFieldDirectivesInstrumentation.class.getName()
            + "$TaintSingleFormFieldDirectiveNewScalaAdvice");
  }

  private void transformDirective(
      MethodTransformer transformation, String methodName, String adviceClass) {
    transformation.applyAdvice(
        isTraitDirectiveMethod(TRAIT_CLASS, methodName),
        ParameterDirectivesInstrumentation.class.getName() + "$" + adviceClass);
  }

  static class TaintSingleFormFieldDirectiveOldScalaAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    static void after(
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Directive retval,
        @Advice.Argument(1) FormFieldDirectives.FieldMagnet fmag) {
      try {
        retval =
            retval.tmap(new TaintSingleParameterFunction<>(fmag), Tupler$.MODULE$.forTuple(null));
      } catch (Exception e) {
        throw new RuntimeException(e); // propagate so it's logged
      }
    }
  }

  static class TaintSingleFormFieldDirectiveNewScalaAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    static void after(
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Directive retval,
        @Advice.Argument(0) FormFieldDirectives.FieldMagnet fmag) {
      try {
        retval =
            retval.tmap(new TaintSingleParameterFunction<>(fmag), Tupler$.MODULE$.forTuple(null));
      } catch (Exception e) {
        throw new RuntimeException(e); // propagate so it's logged
      }
    }
  }
}
