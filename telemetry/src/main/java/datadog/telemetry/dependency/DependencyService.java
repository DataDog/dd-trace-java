package datadog.telemetry.dependency;

import java.net.URL;
import java.util.Collection;

public interface DependencyService {
  Collection<Dependency> drainDeterminedDependencies();

  void addURL(URL uri);

  void stop();
}
