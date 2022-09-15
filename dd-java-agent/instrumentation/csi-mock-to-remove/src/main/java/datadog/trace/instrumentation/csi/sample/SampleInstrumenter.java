package datadog.trace.instrumentation.csi.sample;

import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteInstrumenter;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class SampleInstrumenter extends CallSiteInstrumenter {

  public SampleInstrumenter() {
    super(SampleCallSite.SampleCallSiteSpi.class, "csi-sample");
  }

  @Override
  public ElementMatcher<TypeDescription> callerType() {
    return nameStartsWith("com.test");
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return true;
  }
}
