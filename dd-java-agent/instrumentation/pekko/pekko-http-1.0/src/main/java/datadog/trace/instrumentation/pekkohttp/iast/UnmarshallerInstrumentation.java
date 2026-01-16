package datadog.trace.instrumentation.pekkohttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
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
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.instrumentation.pekkohttp.iast.helpers.TaintFutureHelper;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.pekko.http.javadsl.unmarshalling.Unmarshaller;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;

/**
 * Unconditionally taints an {@link Unmarshaller}'s output if its input is tainted. Note that this
 * only targets the unmarshallers included in pekko-http.
 *
 * <p>This enables propagation when unmarshallers are applied to the output of other unmarshallers.
 * If, on the other hand, the unmarshallers transform input before passing it to their inner
 * unmarshallers, this propagation mechanism will not work.
 */
@AutoService(InstrumenterModule.class)
public class UnmarshallerInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public UnmarshallerInstrumentation() {
    super("pekko-http");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".helpers.TaintFutureHelper",
    };
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameStartsWith("org.apache.pekko.http.scaladsl.unmarshalling.")
        .and(implementsInterface(named(hierarchyMarkerType())));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("apply"))
            .and(returns(named("scala.concurrent.Future")))
            .and(takesArguments(3))
            .and(takesArgument(0, Object.class))
            .and(takesArgument(1, named("scala.concurrent.ExecutionContext")))
            .and(takesArgument(2, named("org.apache.pekko.stream.Materializer"))),
        UnmarshallerInstrumentation.class.getName() + "$PropagateTaintOnApplyAdvice");
  }

  static class PropagateTaintOnApplyAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    static void after(
        @Advice.Return(readOnly = false) Future<?> result,
        @Advice.Argument(0) Object input,
        @Advice.Argument(1) ExecutionContext ec) {
      PropagationModule mod = InstrumentationBridge.PROPAGATION;
      if (mod == null) {
        return;
      }
      result = TaintFutureHelper.wrapFuture(result, input, mod, ec);
    }
  }
}
