package  datadog.trace.instrumentation.axis1;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.axis1.AxisMessageDecorator.AXIS2_MESSAGE;
import static datadog.trace.instrumentation.axis1.AxisMessageDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.axis.MessageContext;

@AutoService(InstrumenterModule.class)
public final class AxisClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public AxisClientInstrumentation() {super("axis1");}


  @Override
  public String hierarchyMarkerType() {
    return "org.apache.axis.Handler";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
        packageName + ".AxisMessageDecorator",
    };
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("invoke"))
            .and(takesArgument(0, named("org.apache.axis.MessageContext"))),
        getClass().getName() + "$ClientInvokeMessageAdvice");
  }

  public static final class ClientInvokeMessageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginInvoke(
        @Advice.Argument(0) final MessageContext message)  {
      if (null == message ){
        return null;
      }

      if (null == message.getOperation()){
        return null;
      }

      AgentSpan span = activeSpan();
      if (null != span && !DECORATE.sameTrace(span, message)) {
        DECORATE.afterStart(span);
        DECORATE.onMessage(span, message);
        activateSpan(span);
      }

/*      if (null != scope) {
        if (!DECORATE.sameTrace(scope.span(), message)) {
          AgentSpan  span = startSpan(AXIS2_MESSAGE);
          DECORATE.afterStart(span);
          DECORATE.onMessage(span, message);
          return activateSpan(span);
        }
      }*/
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class,suppress = Throwable.class)
    public static void endInvoke(
        @Advice.Enter final AgentScope scope,
        @Advice.Argument(0) final MessageContext message,
        @Advice.Thrown final Throwable error){
      if (null == message){
        return ;
      }
      if (null == scope) {
        return;
      }
      System.out.println("getSoapAction name"+message.getOperation().getSoapAction());
      AgentSpan span = scope.span();
      if (null != error) {
        DECORATE.onError(span, error);
      }
      DECORATE.beforeFinish(span, message);
      scope.close();
      span.finish();
    }
  }
}
