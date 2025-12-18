package datadog.trace.instrumentation.thrift;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.thrift.ThriftClientDecorator.CLIENT_DECORATOR;
import static datadog.trace.instrumentation.thrift.ThriftConstants.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.thrift.TBase;
import org.apache.thrift.TServiceClient;
import java.util.Collections;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class TServiceClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public TServiceClientInstrumentation() {
    super(INSTRUMENTATION_NAME, INSTRUMENTATION_NAME_CLIENT);
  }

  @Override
  public String hierarchyMarkerType() {
    return TSERVICE_CLIENT;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(isConstructor()
            .and(takesArgument(1, named("org.apache.thrift.protocol.TProtocol")))
        , packageName + ".TClientConstructorAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(isPrivate())
            .and(named("sendBase"))
            .and(takesArguments(3))
            .and(takesArgument(0, String.class))
            .and(takesArgument(1, named("org.apache.thrift.TBase"))),
        getClass().getName() + "$SendBaseAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(isProtected())
//            .and(takesArgument(1,String.class))
            .and(named("receiveBase")),
        getClass().getName() + "$ReceiveBaseAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".ThriftConstants",
        packageName + ".ThriftBaseDecorator",
        packageName + ".ThriftClientDecorator",
        packageName + ".ThriftConstants$Tags",
        packageName + ".AbstractContext",
        packageName + ".ClientOutProtocolWrapper",
        packageName + ".ServerInProtocolWrapper",
        packageName + ".TClientConstructorAdvice",
        packageName + ".InjectAdepter",
        packageName + ".Context"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.apache.thrift.TServiceClient", AgentScope.class.getName());
  }

  public static class SendBaseAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This TServiceClient tServiceClient,
                                     @Advice.Argument(0) String methodName,
                                     @Advice.Argument(1) TBase tb) {
      AgentSpan span = CLIENT_DECORATOR.createSpan(methodName, tb);
      AgentScope agentScope = activateSpan(span);
      InstrumentationContext.get(TServiceClient.class, AgentScope.class).put(tServiceClient, agentScope);
      return agentScope;
    }

//    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
//    public static void onExit(
//        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
//      if (scope == null) {
//        return;
//      }
//      System.out.println("SendBaseAdvice: onExit close span ");
//      CLIENT_DECORATOR.onError(scope.span(), throwable);
//      CLIENT_DECORATOR.beforeFinish(scope.span());
//      scope.close();
//      scope.span().finish();
//      CLIENT_INJECT_THREAD.remove();
//      System.out.println("CLIENT_INJECT_THREAD remove");
//    }
  }

  public static class ReceiveBaseAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(@Advice.This TServiceClient tServiceClient,
        @Advice.Thrown final Throwable throwable) {
      AgentScope scope = InstrumentationContext.get(TServiceClient.class, AgentScope.class).get(tServiceClient);
      if(scope==null){
        return;
      }
      AgentSpan span = scope.span();
      // AgentScope scope = activeScope();
      if (span==null){
        return;
      }
      CLIENT_DECORATOR.onError(span, throwable);
      CLIENT_DECORATOR.beforeFinish(span);
      span.finish();
      CLIENT_INJECT_THREAD.remove();
      scope.close();
      InstrumentationContext.get(TServiceClient.class, AgentScope.class).remove(tServiceClient);
    }
  }
}
