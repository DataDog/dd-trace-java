package datadog.telemetry.dependency;

import java.util.Objects;
import javax.annotation.Nullable;

public final class Dependency {

  public final String name;
  public final String version;
  public final String source;
  public final String hash;

  public Dependency(String name, String version, String source, @Nullable String hash) {
    this.name = name;
    this.version = version;
    this.source = source;
    this.hash = hash;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Dependency that = (Dependency) o;
    return Objects.equals(name, that.name)
        && Objects.equals(version, that.version)
        && Objects.equals(source, that.source)
        && Objects.equals(hash, that.hash);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, version, source, hash);
  }

  @Override
  public String toString() {
    return "Dependency{"
        + "name='"
        + name
        + '\''
        + ", version='"
        + version
        + '\''
        + ", source='"
        + source
        + '\''
        + ", hash='"
        + hash
        + '\''
        + '}';
  }
}
