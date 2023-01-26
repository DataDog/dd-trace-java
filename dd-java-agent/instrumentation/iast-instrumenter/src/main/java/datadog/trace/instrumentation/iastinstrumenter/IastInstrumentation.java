package datadog.trace.instrumentation.iastinstrumenter;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteInstrumentation;
import datadog.trace.api.iast.IastAdvice;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class IastInstrumentation extends CallSiteInstrumentation {

  public IastInstrumentation() {
    super(IastAdvice.class, "IastInstrumentation");
  }

  @Override
  public ElementMatcher<TypeDescription> callerType() {
    return IastMatcher.INSTANCE;
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TargetSystem.IAST);
  }

  public static final class IastMatcher
      extends ElementMatcher.Junction.ForNonNullValues<TypeDescription> {
    public static final IastMatcher INSTANCE = new IastMatcher();

    @Override
    protected boolean doMatch(TypeDescription target) {
      return IastExclusionTrie.apply(target.getName()) != 1;
    }
  }
}
