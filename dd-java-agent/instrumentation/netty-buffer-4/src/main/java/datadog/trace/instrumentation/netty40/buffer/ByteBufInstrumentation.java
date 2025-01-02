package datadog.trace.instrumentation.netty40.buffer;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;

@AutoService(InstrumenterModule.class)
public class ByteBufInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType,
        Instrumenter.HasTypeAdvice,
        Instrumenter.HasMethodAdvice {

  private final String className = ByteBufInstrumentation.class.getName();

  public ByteBufInstrumentation() {
    super("netty", "netty-4.0");
  }

  @Override
  public String instrumentedType() {
    return "io.netty.buffer.ByteBuf";
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new TaintableVisitor(instrumentedType()));
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    // TODO add propagation if needed
  }
}
