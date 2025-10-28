package datadog.trace.instrumentation.vertx_3_4.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.PARSABLE_HEADER_VALUE;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.VIRTUAL_HOST_HANDLER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

/** Propagation is way easier in io.vertx.core.buffer.impl.BufferImpl than in io.netty.Buffer */
@AutoService(InstrumenterModule.class)
public class BufferInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType,
        Instrumenter.HasTypeAdvice,
        Instrumenter.HasMethodAdvice {

  private final String className = BufferInstrumentation.class.getName();

  public BufferInstrumentation() {
    super("vertx", "vertx-3.4");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {PARSABLE_HEADER_VALUE, VIRTUAL_HOST_HANDLER};
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.core.buffer.impl.BufferImpl";
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new TaintableVisitor(instrumentedType()));
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("toString")), className + "$ToStringAdvice");
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("getByteBuf")).and(takesNoArguments()),
        className + "$GetByteBuffAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("appendBuffer"))
            .and(takesArgument(0, named("io.vertx.core.buffer.Buffer"))),
        className + "$AppendBufferAdvice");
  }

  public static class ToStringAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void get(@Advice.This final Object self, @Advice.Return final String result) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (result != null && module != null) {
        module.taintStringIfTainted(result, self);
      }
    }
  }

  public static class GetByteBuffAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void get(@Advice.This final Object self, @Advice.Return final Object result) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (result != null && module != null) {
        module.taintObjectIfTainted(result, self);
      }
    }
  }

  public static class AppendBufferAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void get(
        @Advice.Argument(0) final Object buffer, @Advice.Return final Object result) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (result != null && module != null) {
        module.taintObjectIfTainted(result, buffer);
      }
    }
  }
}
