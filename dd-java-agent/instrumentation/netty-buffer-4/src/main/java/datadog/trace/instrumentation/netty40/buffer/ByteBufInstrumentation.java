package datadog.trace.instrumentation.netty40.buffer;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;

@AutoService(Instrumenter.class)
public class ByteBufInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  private final String className = ByteBufInstrumentation.class.getName();

  public ByteBufInstrumentation() {
    super("netty", "netty-4.0");
  }

  @Override
  public String instrumentedType() {
    return "io.netty.buffer.ByteBuf";
  }

  @Override
  public void adviceTransformations(final AdviceTransformation transformation) {
    // TODO add propagation if needed
  }

  @Override
  public AdviceTransformer transformer() {
    return new VisitingTransformer(new TaintableVisitor(instrumentedType()));
  }
}
