package datadog.trace.instrumentation.csi.iast;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteInstrumenter;
import datadog.trace.api.Config;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class IastInstrumenter extends CallSiteInstrumenter
    implements ElementMatcher<TypeDescription> {

  public IastInstrumenter() {
    super(IastAdvice.class, "iast");
  }

  public ElementMatcher<TypeDescription> callerType() {
    return this;
  }

  @Override
  public boolean matches(final TypeDescription target) {
    return IastTrie.apply(target.getName()) != 1;
  }

  @Override
  public boolean isEnabled() {
    return Config.get().isIastEnabled();
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TargetSystem.IAST);
  }
}
