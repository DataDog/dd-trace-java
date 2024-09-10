package datadog.trace.agent.tooling;

import datadog.trace.agent.tooling.bytebuddy.outline.WithLocation;
import datadog.trace.api.civisibility.InstrumentationBridge;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.BitSet;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;

// FIXME nikita: rename?
public class CiVisibilityMatcher implements AgentBuilder.RawMatcher {

  private final AgentBuilder.RawMatcher delegate;

  public CiVisibilityMatcher(AgentBuilder.RawMatcher delegate) {
    this.delegate = delegate;
  }

  @Override
  public boolean matches(
      TypeDescription target,
      ClassLoader classLoader,
      JavaModule module,
      Class<?> classBeingRedefined,
      ProtectionDomain pd) {

    if (!(target instanceof WithLocation)) {
      return delegate.matches(target, classLoader, module, classBeingRedefined, pd);
    }

    WithLocation targetWithLocation = (WithLocation) target;
    URL classFile = targetWithLocation.getClassFile();
    String name = target.getName();
    BitSet recordedIds = InstrumentationBridge.getRecordedMatchingResult(name, classFile);
    if (recordedIds != null) {
      BitSet ids = CombiningMatcher.recordedMatches.get();
      ids.clear();
      ids.or(recordedIds);
      return !ids.isEmpty();
    }

    boolean result = delegate.matches(target, classLoader, module, classBeingRedefined, pd);
    InstrumentationBridge.recordMatchingResult(
        name, classFile, CombiningMatcher.recordedMatches.get());
    return result;
  }
}
