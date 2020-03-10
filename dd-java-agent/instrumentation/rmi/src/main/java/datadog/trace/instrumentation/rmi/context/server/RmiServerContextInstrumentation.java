package datadog.trace.instrumentation.rmi.context.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.instrumentation.rmi.context.ContextPropagator.DD_CONTEXT_CALL_ID;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import sun.rmi.transport.Target;

@AutoService(Instrumenter.class)
public class RmiServerContextInstrumentation extends Instrumenter.Default {

  public RmiServerContextInstrumentation() {
    super("rmi", "rmi-context-propagator", "rmi-server-context-propagator");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return extendsClass(named("sun.rmi.transport.ObjectTable"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.rmi.context.ContextPayload$InjectAdapter",
      "datadog.trace.instrumentation.rmi.context.ContextPayload$ExtractAdapter",
      "datadog.trace.instrumentation.rmi.context.ContextPayload",
      "datadog.trace.instrumentation.rmi.context.ContextPropagator",
      packageName + ".ContextDispatcher",
      packageName + ".ContextDispatcher$NoopRemote"
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(isStatic())
            .and(named("getTarget"))
            .and((takesArgument(0, named("sun.rmi.transport.ObjectEndpoint")))),
        getClass().getName() + "$ObjectTableAdvice");
  }

  public static class ObjectTableAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void methodExit(
        @Advice.Argument(0) final Object oe, @Advice.Return(readOnly = false) Target result) {
      // comparing toString() output allows us to avoid using reflection to be able to compare
      // ObjID and ObjectEndpoint objects
      // ObjectEndpoint#toString() only returns this.objId.toString() value which is exactly
      // what we're interested in here.
      if (!DD_CONTEXT_CALL_ID.toString().equals(oe.toString())) {
        return;
      }
      result = ContextDispatcher.newDispatcherTarget();
    }
  }
}
