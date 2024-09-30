package datadog.trace.api.civisibility.domain;

import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import javax.annotation.Nonnull;

public class SourceSet implements Serializable {

  public enum Type {
    CODE,
    TEST
  }

  private final Type type;
  private final Collection<File> sources;
  private final Collection<File> destinations;

  public SourceSet(
      Type type, @Nonnull Collection<File> sources, @Nonnull Collection<File> destinations) {
    this.type = type;
    this.sources = sources;
    this.destinations = destinations;
  }

  public Type getType() {
    return type;
  }

  @Nonnull
  public Collection<File> getSources() {
    return sources;
  }

  @Nonnull
  public Collection<File> getDestinations() {
    return destinations;
  }
}
