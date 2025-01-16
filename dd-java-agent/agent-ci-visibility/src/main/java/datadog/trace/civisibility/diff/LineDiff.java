package datadog.trace.civisibility.diff;

import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** Diff data with per-line granularity. */
public class LineDiff implements Diff {

  public static final LineDiff EMPTY = new LineDiff(Collections.emptyMap());

  private final Map<String, BitSet> linesByRelativePath;

  public LineDiff(Map<String, BitSet> linesByRelativePath) {
    this.linesByRelativePath = linesByRelativePath;
  }

  public Map<String, BitSet> getLinesByRelativePath() {
    return Collections.unmodifiableMap(linesByRelativePath);
  }

  @Override
  public boolean contains(String relativePath, int startLine, int endLine) {
    BitSet lines = linesByRelativePath.get(relativePath);
    if (lines == null) {
      return false;
    }

    int changedLine = lines.nextSetBit(startLine);
    return changedLine != -1 && changedLine <= endLine;
  }

  @Override
  public void serialize(Serializer s) {
    s.write(linesByRelativePath, Serializer::write, Serializer::write);
  }

  public static LineDiff deserialize(ByteBuffer buffer) {
    Map<String, BitSet> linesByRelativePath =
        Serializer.readMap(buffer, Serializer::readString, Serializer::readBitSet);
    return new LineDiff(linesByRelativePath);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    LineDiff diff = (LineDiff) o;
    return Objects.equals(linesByRelativePath, diff.linesByRelativePath);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(linesByRelativePath);
  }

  @Override
  public String toString() {
    return "LineDiff{linesByRelativePath=" + linesByRelativePath + '}';
  }
}
