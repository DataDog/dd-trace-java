package datadog.trace.agent.tooling.iast.stratum;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StratumExt extends AbstractStratum implements Stratum {
  private final List<FileInfo> fileInfo = new ArrayList<FileInfo>();

  private int[] lineStart = null;

  private final List<LineInfo> lineInfo = new ArrayList<LineInfo>();

  private static final Logger LOG = LoggerFactory.getLogger(StratumExt.class);

  public StratumExt() {
    this("");
  }

  public StratumExt(final String name) {
    super(name);
  }

  @Override
  public int getInputLineNumber(final int outputLineNumber) {
    try {
      List<LineInfo> info = getLineInfo();
      int startPoint = Arrays.binarySearch(getLineStart(), outputLineNumber);
      if (startPoint < 0) {
        if (startPoint == -1) {
          startPoint = 0;
        } else {
          startPoint = Math.abs(startPoint) - 2;
        }
      }
      int size = info.size();
      for (int i = startPoint; i < size; i++) {
        LineInfo li = info.get(i);
        final int start = li.outputStartLine;
        if (outputLineNumber >= start) {
          int offset = li.repeatCount * li.outputLineIncrement - 1;
          int stop = li.outputStartLine + offset;
          if (outputLineNumber <= stop) {
            int rc = (outputLineNumber - li.outputStartLine) / li.outputLineIncrement;
            return li.inputStartLine + rc;
          }
        }
      }
    } catch (Exception e) {
      LOG.error("Could not get input line number from stratum", e);
    }
    return 0;
  }

  @Override
  public String getSourceFile() {
    if (fileInfo.isEmpty()) {
      return null;
    }
    return fileInfo.get(0).getInputFilePath();
  }

  public List<FileInfo> getFileInfo() {
    return fileInfo;
  }

  public void setFileInfo(final List<FileInfo> fileInfoList) {
    fileInfo.clear();
    if (fileInfoList != null) {
      fileInfo.addAll(fileInfoList);
    }
  }

  public List<LineInfo> getLineInfo() {
    return lineInfo;
  }

  public void addLineInfo(final LineInfo info) {
    lineInfo.add(info);
    Collections.sort(
        lineInfo,
        new Comparator<LineInfo>() {

          @Override
          public int compare(final LineInfo o1, final LineInfo o2) {
            return o1.getOutputStartLine() - o2.getOutputStartLine();
          }
        });
  }

  public int[] getLineStart() {
    if (lineStart == null) {
      lineStart = new int[lineInfo.size()];
      for (int i = 0; i < lineStart.length; i++) {
        lineStart[i] = lineInfo.get(i).getOutputStartLine();
      }
    }
    return lineStart;
  }

  @Override
  public String toString() {
    return "Stratum [fileInfoList=" + fileInfo + ", lineInfoList=" + lineInfo + "]";
  }
}
