package datadog.trace.instrumentation.akkahttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.server.directives.MarshallingDirectives$;
import akka.http.scaladsl.unmarshalling.Unmarshaller;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.instrumentation.akkahttp.iast.helpers.TaintUnmarshaller;
import net.bytebuddy.asm.Advice;

/**
 * Instruments {@link MarshallingDirectives$#entity(Unmarshaller)} in order to wrap the marshaller
 * so that the input it gets is tainted (source).
 *
 * @see UnmarshallerInstrumentation unconditionally taints marshaller output if its input is tainted
 */
public class MarshallingDirectivesInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "akka.http.scaladsl.server.directives.MarshallingDirectives$class",
      "akka.http.scaladsl.server.directives.MarshallingDirectives",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("entity"))
            .and(returns(named("akka.http.scaladsl.server.Directive")))
            .and(takesArguments(2))
            .and(
                takesArgument(
                    0, named("akka.http.scaladsl.server.directives.MarshallingDirectives")))
            .and(takesArgument(1, named("akka.http.scaladsl.unmarshalling.Unmarshaller"))),
        MarshallingDirectivesInstrumentation.class.getName()
            + "$TaintUnmarshallerInputOldScalaAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("entity"))
            .and(returns(named("akka.http.scaladsl.server.Directive")))
            .and(takesArguments(1))
            .and(takesArgument(1, named("akka.http.scaladsl.unmarshalling.Unmarshaller"))),
        MarshallingDirectivesInstrumentation.class.getName()
            + "$TaintUnmarshallerInputNewScalaAdvice");
  }

  static class TaintUnmarshallerInputOldScalaAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_BODY)
    static void before(@Advice.Argument(readOnly = false, value = 1) Unmarshaller unmarshaller) {
      PropagationModule mod = InstrumentationBridge.PROPAGATION;
      if (mod != null) {
        unmarshaller = new TaintUnmarshaller(mod, unmarshaller);
      }
    }
  }

  static class TaintUnmarshallerInputNewScalaAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_BODY)
    static void before(@Advice.Argument(readOnly = false, value = 0) Unmarshaller unmarshaller) {
      PropagationModule mod = InstrumentationBridge.PROPAGATION;
      if (mod != null) {
        unmarshaller = new TaintUnmarshaller(mod, unmarshaller);
      }
    }
  }
}
