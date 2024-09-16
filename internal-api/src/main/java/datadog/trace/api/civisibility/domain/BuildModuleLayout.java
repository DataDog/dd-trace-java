package datadog.trace.api.civisibility.domain;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

public class BuildModuleLayout implements Serializable {

  private final List<SourceSet> sourceSets;

  public BuildModuleLayout(@Nonnull Collection<SourceSet> sourceSets) {
    this.sourceSets = sourceSets.stream().filter(Objects::nonNull).collect(Collectors.toList());
  }

  @Nonnull
  public List<SourceSet> getSourceSets() {
    return sourceSets;
  }
}
