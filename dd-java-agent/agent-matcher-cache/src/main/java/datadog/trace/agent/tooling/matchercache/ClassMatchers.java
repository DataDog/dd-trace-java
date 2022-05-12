package datadog.trace.agent.tooling.matchercache;

import net.bytebuddy.description.type.TypeDescription;

public interface ClassMatchers {

  boolean isGloballyIgnored(String fullClassName, boolean skipAdditionalIgnores);

  boolean matchesAny(TypeDescription typeDescription);
}
