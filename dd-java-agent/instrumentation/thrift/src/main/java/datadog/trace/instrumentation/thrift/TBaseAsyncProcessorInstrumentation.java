package datadog.trace.instrumentation.thrift;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.thrift.ThriftConstants.*;
import static datadog.trace.instrumentation.thrift.ThriftServerDecorator.SERVER_DECORATOR;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.thrift.TBaseAsyncProcessor;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.server.AbstractNonblockingServer;

@AutoService(InstrumenterModule.class)
public class TBaseAsyncProcessorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy , Instrumenter.HasMethodAdvice{

  public TBaseAsyncProcessorInstrumentation() {
    super(INSTRUMENTATION_NAME, INSTRUMENTATION_NAME_SERVER);
  }

  @Override
  public String hierarchyMarkerType() {
    return T_BASE_ASYNC_PROCESSOR;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(isMethod()
            .and(isPublic())
            .and(named("process"))
        , getClass().getName() + "$AsyncProcessAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".ThriftConstants",
        packageName + ".ThriftBaseDecorator",
        packageName + ".ThriftConstants$Tags",
        packageName + ".AbstractContext",
        packageName + ".ServerInProtocolWrapper",
        packageName + ".ExtractAdepter",
        packageName + ".CTProtocolFactory",
        packageName + ".STProtocolFactory",
        packageName + ".ThriftServerDecorator",
        packageName + ".AsyncContext",
        packageName + ".Context"
    };
  }

  public static class AsyncProcessAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This final TBaseAsyncProcessor tBaseAsyncProcessor
        , @Advice.AllArguments final Object[] args) {
      try {
        System.out.println("do AsyncProcessAdvice onEnter");
        TProtocol protocol = ((AbstractNonblockingServer.AsyncFrameBuffer) args[0]).getInputProtocol();
        ((ServerInProtocolWrapper) protocol).initial(new AsyncContext(tBaseAsyncProcessor.getProcessMapView()));
      }catch (Exception e){
        e.printStackTrace();
      }
      return activateSpan(noopSpan());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(@Advice.Thrown final Throwable throwable) {
      AgentSpan span = activeSpan();
      if (span != null) {
        SERVER_DECORATOR.onError(span, throwable);
        SERVER_DECORATOR.beforeFinish(span);

        span.finish();
        CONTEXT_THREAD.remove();
      }
    }
  }
}
