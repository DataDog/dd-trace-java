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
import org.apache.thrift.TBaseProcessor;

@AutoService(InstrumenterModule.class)
public class TBaseProcessorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy , Instrumenter.HasMethodAdvice{

  public TBaseProcessorInstrumentation() {
    super(INSTRUMENTATION_NAME, INSTRUMENTATION_NAME_SERVER);
  }

  @Override
  public String hierarchyMarkerType() {
    return T_BASE_PROCESSOR;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(T_BASE_PROCESSOR));
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(isMethod()
            .and(isPublic())
            .and(named("process"))
        , getClass().getName() + "$ProcessAdvice");
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
        packageName + ".Context"
    };
  }

  public static class ProcessAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(@Advice.This final Object obj
        , @Advice.AllArguments final Object[] args) {
      System.out.println("do ProcessAdvice onEnter");
      if (obj instanceof TBaseProcessor) {
        try {
          Object in = args[0];
          if (in instanceof ServerInProtocolWrapper) {
            TBaseProcessor tBaseProcessor = (TBaseProcessor) obj;
            ((ServerInProtocolWrapper) in).initial(new Context(tBaseProcessor.getProcessMapView()));
          }
        }catch (Exception e){
          e.printStackTrace();
          throw e;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(@Advice.Thrown final Throwable throwable) {
      AgentSpan span  = activeSpan();
      if (span!=null) {
        System.out.println("finish ProcessAdvice span.");
        SERVER_DECORATOR.onError(span, throwable);
        SERVER_DECORATOR.beforeFinish(span);
        span.finish();
        CONTEXT_THREAD.remove();
      }
    }
  }
}
