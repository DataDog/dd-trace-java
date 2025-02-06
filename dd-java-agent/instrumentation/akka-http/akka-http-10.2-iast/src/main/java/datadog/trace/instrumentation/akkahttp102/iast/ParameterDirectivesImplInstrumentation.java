package datadog.trace.instrumentation.akkahttp102.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.server.Directive;
import akka.http.scaladsl.server.util.Tupler$;
import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.instrumentation.akkahttp102.iast.helpers.TaintParametersFunction;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class ParameterDirectivesImplInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ParameterDirectivesImplInstrumentation() {
    super("akka-http");
  }

  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.server.directives.ParameterDirectives$Impl$";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".helpers.TaintParametersFunction",
    };
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    // just so we can use assertInverse in the muzzle directive
    return new Reference[] {new Reference.Builder(instrumentedType()).build()};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("filter"))
            .and(takesArguments(2))
            .and(takesArgument(0, String.class))
            .and(takesArgument(1, named("akka.http.scaladsl.unmarshalling.Unmarshaller")))
            .and(returns(named("akka.http.scaladsl.server.Directive"))),
        ParameterDirectivesImplInstrumentation.class.getName() + "$FilterAdvice");

    // requiredFilter not relevant
    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("repeatedFilter"))
            .and(takesArguments(2))
            .and(takesArgument(0, String.class))
            .and(takesArgument(1, named("akka.http.scaladsl.unmarshalling.Unmarshaller")))
            .and(returns(named("akka.http.scaladsl.server.Directive"))),
        ParameterDirectivesImplInstrumentation.class.getName() + "$RepeatedFilterAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  static class FilterAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    static void after(
        @Advice.Argument(0) String paramName,
        @Advice.Return(readOnly = false) Directive /*<Tuple1<?>>*/ retval,
        @ActiveRequestContext RequestContext reqCtx) {
      try {
        IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
        retval =
            retval.tmap(
                new TaintParametersFunction(ctx, paramName), Tupler$.MODULE$.forTuple(null));
      } catch (Exception e) {
        throw new RuntimeException(e); // propagate so it's logged
      }
    }
  }

  @RequiresRequestContext(RequestContextSlot.IAST)
  static class RepeatedFilterAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    static void after(
        @Advice.Argument(0) String paramName,
        @Advice.Return(readOnly = false) Directive /*<Tuple1<Iterable<?>>>*/ retval,
        @ActiveRequestContext RequestContext reqCtx) {
      try {
        IastContext ctx = reqCtx.getData(RequestContextSlot.IAST);
        retval =
            retval.tmap(
                new TaintParametersFunction(ctx, paramName), Tupler$.MODULE$.forTuple(null));
      } catch (Exception e) {
        throw new RuntimeException(e); // propagate so it's logged
      }
    }
  }
}
