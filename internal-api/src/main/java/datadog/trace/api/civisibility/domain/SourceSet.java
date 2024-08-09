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
  private final File output;

  public SourceSet(Type type, @Nonnull Collection<File> sources, File output) {
    this.type = type;
    this.sources = sources;
    this.output = output;
  }

  public Type getType() {
    return type;
  }

  @Nonnull
  public Collection<File> getSources() {
    return sources;
  }

  public File getOutput() {
    return output;
  }
}
