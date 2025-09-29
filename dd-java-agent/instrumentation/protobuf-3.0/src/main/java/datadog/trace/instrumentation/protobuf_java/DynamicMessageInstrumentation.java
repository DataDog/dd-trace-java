package datadog.trace.instrumentation.protobuf_java;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class DynamicMessageInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  static final String instrumentationName = "protobuf";
  static final String TARGET_TYPE = "com.google.protobuf.DynamicMessage";

  public DynamicMessageInstrumentation() {
    super(instrumentationName);
  }

  @Override
  public String instrumentedType() {
    return TARGET_TYPE;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SchemaExtractor",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("parseFrom"))
            .and(isStatic())
            .and(takesArgument(0, named("com.google.protobuf.Descriptors$Descriptor")))
            .and(returns(named(TARGET_TYPE))),
        DynamicMessageInstrumentation.class.getName() + "$ParseFromAdvice");
  }

  public static class ParseFromAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final Descriptor descriptor) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(DynamicMessage.class);
      if (callDepth > 0) {
        return;
      }
      SchemaExtractor.attachSchemaOnSpan(descriptor, activeSpan(), SchemaExtractor.deserialization);
    }

    @Advice.OnMethodExit()
    public static void trackDepth() {
      CallDepthThreadLocalMap.decrementCallDepth(DynamicMessage.class);
    }
  }
}
