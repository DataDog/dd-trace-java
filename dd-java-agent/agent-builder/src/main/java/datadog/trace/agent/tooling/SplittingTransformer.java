package datadog.trace.agent.tooling;

import java.security.ProtectionDomain;
import java.util.BitSet;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/** Splits matches recorded by {@link CombiningMatcher} back into separate transformation steps. */
final class SplittingTransformer implements AgentBuilder.Transformer {
  private final AdviceStack[] transformers;

  SplittingTransformer(AdviceStack[] transformers) {
    this.transformers = transformers;
  }

  @Override
  public DynamicType.Builder<?> transform(
      DynamicType.Builder<?> builder,
      TypeDescription target,
      ClassLoader classLoader,
      JavaModule module,
      ProtectionDomain pd) {

    BitSet ids = CombiningMatcher.recordedMatches.get();
    for (int id = ids.nextSetBit(0); id >= 0; id = ids.nextSetBit(id + 1)) {
      long fromTick = InstrumenterMetrics.tick();
      builder = transformers[id].transform(builder, target, classLoader, module, pd);
      InstrumenterMetrics.transformType(fromTick);
    }

    return builder;
  }
}
