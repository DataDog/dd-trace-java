import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Set;

@AutoService(Instrumenter.class)
public class NoOpInstrumentation implements Instrumenter {
  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return true; // always on for testing purposes
  }

  @Override
  public void instrument(TransformerBuilder transformerBuilder) {
    // no-op
  }
}
