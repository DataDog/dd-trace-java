package datadog.trace.bootstrap.instrumentation.ci.codeowners;

import java.util.Collection;
import javax.annotation.Nullable;

public interface Codeowners {
  @Nullable
  Collection<String> getOwners(String path);
}
