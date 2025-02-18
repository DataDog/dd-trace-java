package datadog.trace.civisibility.diff;

import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/** Diff data with per-file granularity. */
public class FileDiff implements Diff {

  private final @Nonnull Set<String> changedFiles;

  public FileDiff(@Nonnull Set<String> changedFiles) {
    this.changedFiles = changedFiles;
  }

  @Override
  public boolean contains(String relativePath, int startLine, int endLine) {
    return changedFiles.contains(relativePath);
  }

  @Override
  public void serialize(Serializer s) {
    s.write(changedFiles);
  }

  public static FileDiff deserialize(ByteBuffer buffer) {
    return new FileDiff(Serializer.readSet(buffer, Serializer::readString));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    FileDiff diff = (FileDiff) o;
    return Objects.equals(changedFiles, diff.changedFiles);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(changedFiles);
  }

  @Override
  public String toString() {
    return "FileDiff{changedFiles=" + changedFiles + '}';
  }
}
