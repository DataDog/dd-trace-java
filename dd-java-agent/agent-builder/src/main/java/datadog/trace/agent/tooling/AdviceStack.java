package datadog.trace.agent.tooling;

import java.security.ProtectionDomain;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/** Stack of advice transformations declared by a particular instrumentation. */
final class AdviceStack implements AgentBuilder.Transformer {
  private final AgentBuilder.Transformer[] advices;

  AdviceStack(List<AgentBuilder.Transformer> advices) {
    this.advices = advices.toArray(new AgentBuilder.Transformer[0]);
  }

  AdviceStack(AgentBuilder.Transformer advice) {
    this.advices = new AgentBuilder.Transformer[] {advice};
  }

  @Override
  public DynamicType.Builder<?> transform(
      DynamicType.Builder<?> builder,
      TypeDescription target,
      ClassLoader classLoader,
      JavaModule module,
      ProtectionDomain pd) {

    for (AgentBuilder.Transformer advice : advices) {
      builder = advice.transform(builder, target, classLoader, module, pd);
    }

    return builder;
  }
}
