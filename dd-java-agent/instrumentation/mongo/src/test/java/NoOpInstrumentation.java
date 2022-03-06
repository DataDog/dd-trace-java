import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.AsyncMatching;
import java.util.Set;
import net.bytebuddy.agent.builder.AgentBuilder;

@AutoService(Instrumenter.class)
public class NoOpInstrumentation implements Instrumenter {

  @Override
  public AgentBuilder instrument(
      final AgentBuilder agentBuilder, final AsyncMatching asyncMatching) {
    return agentBuilder;
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    // don't care
    return true;
  }
}
