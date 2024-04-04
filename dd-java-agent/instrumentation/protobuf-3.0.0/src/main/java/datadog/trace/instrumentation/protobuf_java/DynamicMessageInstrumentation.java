package datadog.trace.instrumentation.protobuf_java;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.protobuf_java.Decorator.DESERIALIZER_DECORATOR;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.google.protobuf.Descriptors.Descriptor;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class DynamicMessageInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  static final String instrumentationName = "protobuf";
  static final String TARGET_TYPE = "com.google.protobuf.DynamicMessage";
  static final String DESERIALIZE = "deserialize";

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
      packageName + ".OpenAPIFormatExtractor", packageName + ".Decorator",
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
    public static AgentScope onEnter(@Advice.Argument(0) final Descriptor descriptor) {
      final AgentSpan span = startSpan(instrumentationName, DESERIALIZE);
      DESERIALIZER_DECORATOR.attachSchemaOnSpan(descriptor, span);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      AgentSpan span = scope.span();
      DESERIALIZER_DECORATOR.onError(span, throwable);
      scope.close();
      span.finish();
    }
  }
}
