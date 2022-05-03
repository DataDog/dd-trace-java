package com.datadog.appsec.dependency;

import java.net.URI;
import java.util.Collection;

public interface DependencyService {
  Collection<Dependency> determineNewDependencies();

  void addURI(URI uri);
}
