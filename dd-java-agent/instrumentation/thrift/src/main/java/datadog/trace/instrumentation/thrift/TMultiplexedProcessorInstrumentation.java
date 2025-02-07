package datadog.trace.instrumentation.thrift;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.instrumentation.thrift.ThriftConstants.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.thrift.ProcessFunction;
import org.apache.thrift.TMultiplexedProcessor;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TProtocol;

@AutoService(InstrumenterModule.class)
public class TMultiplexedProcessorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public TMultiplexedProcessorInstrumentation() {
    super(INSTRUMENTATION_NAME, INSTRUMENTATION_NAME_SERVER);
  }

  @Override
  public String instrumentedType() {
    return T_MULTIPLEXED_PROCESSOR;
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(
        isConstructor(), getClass().getName() + "$TMultiplexedProcessorConstructorAdvice");

    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("process")),
        getClass().getName() + "$TMultiplexedProcessorProcessAdvice");
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("registerProcessor")),
        getClass().getName() + "$TMultiplexedProcessorRegisterProcessAdvice");
    // 0.12以上版本
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("registerDefault")),
        getClass().getName() + "$TMultiplexedProcessorRegisterDefaultAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
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

  public static class TMultiplexedProcessorConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(@Advice.This final TMultiplexedProcessor processor) {
      TM_M.put(processor, new HashMap<String, ProcessFunction>());
    }
  }

  public static class TMultiplexedProcessorProcessAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final TMultiplexedProcessor processor,
        @Advice.AllArguments final Object[] args) {
      try {
        TProtocol protocol = (TProtocol) args[0];
        ((ServerInProtocolWrapper) protocol).initial(new Context(TM_M.get(processor)));
      } catch (Exception e) {
        throw e;
      }
      return activateSpan(noopSpan());
    }
  }

  public static class TMultiplexedProcessorRegisterProcessAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final TMultiplexedProcessor obj,
        @Advice.AllArguments final Object[] allArguments) {
      Map<String, ProcessFunction> processMap = TM_M.get(obj);
      String serviceName = (String) allArguments[0];
      TProcessor processor = (TProcessor) allArguments[1];
      processMap.putAll(ThriftConstants.getProcessMap(serviceName, processor));
      TM_M.put(obj, processMap);
      return activateSpan(noopSpan());
    }
  }

  public static class TMultiplexedProcessorRegisterDefaultAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final TMultiplexedProcessor obj,
        @Advice.AllArguments final Object[] allArguments) {
      Map<String, ProcessFunction> processMap = TM_M.get(obj);
      TProcessor processor = (TProcessor) allArguments[0];
      processMap.putAll(ThriftConstants.getProcessMap(processor));
      TM_M.put(obj, processMap);
      return activateSpan(noopSpan());
    }
  }
}
