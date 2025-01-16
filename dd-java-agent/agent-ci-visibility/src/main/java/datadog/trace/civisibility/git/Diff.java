package datadog.trace.civisibility.git;

import java.util.BitSet;
import java.util.Collections;
import java.util.Map;

public class Diff {

  public static final Diff EMPTY = new Diff(Collections.emptyMap());

  private final Map<String, BitSet> linesByRelativePath;

  public Diff(Map<String, BitSet> linesByRelativePath) {
    this.linesByRelativePath = linesByRelativePath;
  }

  public Map<String, BitSet> getLinesByRelativePath() {
    return Collections.unmodifiableMap(linesByRelativePath);
  }

  public boolean contains(String relativePath, int startLine, int endLine) {
    BitSet lines = linesByRelativePath.get(relativePath);
    if (lines == null) {
      return false;
    }

    int changedLine = lines.nextSetBit(startLine);
    return changedLine != -1 && changedLine <= endLine;
  }
}
