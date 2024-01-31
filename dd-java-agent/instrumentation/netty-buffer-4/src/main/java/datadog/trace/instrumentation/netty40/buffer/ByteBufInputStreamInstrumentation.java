package datadog.trace.instrumentation.netty40.buffer;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class ByteBufInputStreamInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public ByteBufInputStreamInstrumentation() {
    super("netty", "netty-4.0");
  }

  @Override
  public String instrumentedType() {
    return "io.netty.buffer.ByteBufInputStream";
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new TaintableVisitor(instrumentedType()));
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(isPublic())
            .and(takesArguments(3))
            .and(takesArgument(0, named("io.netty.buffer.ByteBuf")))
            .and(takesArgument(1, int.class))
            .and(takesArgument(2, boolean.class)),
        ByteBufInputStreamInstrumentation.class.getName() + "$ConstructorAdvice");
  }

  public static class ConstructorAdvice {

    @Advice.OnMethodExit
    @Propagation
    public static void onExit(
        @Advice.This final Object self, @Advice.Argument(0) final Object buffer) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      try {
        if (module != null) {
          module.taintIfTainted(self, buffer);
        }
      } catch (final Throwable e) {
        module.onUnexpectedException("ByteBufInputStream ctor threw", e);
      }
    }
  }
}
