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
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.instrumentation.pekkohttp.iast.helpers.TaintUnmarshaller;
import net.bytebuddy.asm.Advice;
import org.apache.pekko.http.scaladsl.server.directives.MarshallingDirectives$;
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller;

/**
 * Instruments {@link MarshallingDirectives$#entity(Unmarshaller)} in order to wrap the marshaller
 * so that the input it gets is tainted (source).
 *
 * @see UnmarshallerInstrumentation unconditionally taints marshaller output if its input is tainted
 */
@AutoService(InstrumenterModule.class)
public class MarshallingDirectivesInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public MarshallingDirectivesInstrumentation() {
    super("pekko-http");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.pekko.http.scaladsl.server.directives.MarshallingDirectives$class",
      "org.apache.pekko.http.scaladsl.server.directives.MarshallingDirectives",
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".helpers.TaintUnmarshaller",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("entity"))
            .and(returns(named("org.apache.pekko.http.scaladsl.server.Directive")))
            .and(takesArguments(2))
            .and(
                takesArgument(
                    0,
                    named(
                        "org.apache.pekko.http.scaladsl.server.directives.MarshallingDirectives")))
            .and(
                takesArgument(
                    1, named("org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller"))),
        MarshallingDirectivesInstrumentation.class.getName()
            + "$TaintUnmarshallerInputOldScalaAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("entity"))
            .and(returns(named("org.apache.pekko.http.scaladsl.server.Directive")))
            .and(takesArguments(1))
            .and(
                takesArgument(
                    1, named("org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller"))),
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
