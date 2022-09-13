package datadog.trace.instrumentation.iastinstrumenter;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteInstrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.IastExclusionTrie;
import datadog.trace.api.iast.IastAdvice;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class IastInstrumenter extends CallSiteInstrumenter
    implements ElementMatcher<TypeDescription> {

  public IastInstrumenter() {
    super(IastAdvice.class, "IastInstrumenter");
  }

  @Override
  public ElementMatcher<TypeDescription> callerType() {
    return this;
  }

  @Override
  public boolean matches(final TypeDescription target) {
    return IastExclusionTrie.apply(target.getName()) != 1;
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TargetSystem.IAST);
  }
}
