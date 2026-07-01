package datadog.trace.agent.tooling.stratum;

import datadog.trace.api.Pair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StratumExt extends AbstractStratum implements Stratum {
  private final List<FileInfo> fileInfo = new ArrayList<>();
  private Map<String, FileInfo> fileInfoMap;

  private int[] lineStart = null;

  private final List<LineInfo> lineInfo = new ArrayList<>();

  private static final Logger LOG = LoggerFactory.getLogger(StratumExt.class);

  public StratumExt(final String name) {
    super(name);
  }

  @Override
  public Pair<String, Integer> getInputLine(final int outputLineNumber) {
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
            return Pair.of(li.getFileId(), li.inputStartLine + rc);
          }
        }
      }
    } catch (Exception e) {
      LOG.debug("Could not get input line number from stratum", e);
    }
    return null;
  }

  @Override
  public String getSourceFile(String fileId) {
    if (fileInfo.isEmpty()) {
      return null;
    }
    populateFileInfoMap();
    FileInfo fileInfo = fileInfoMap.get(fileId);
    return fileInfo != null ? fileInfo.getInputFilePath() : null;
  }

  public String getSourceFileName(String fileId) {
    if (fileInfo.isEmpty()) {
      return null;
    }
    populateFileInfoMap();
    FileInfo fileInfo = fileInfoMap.get(fileId);
    return fileInfo != null ? fileInfo.getInputFileName() : null;
  }

  private void populateFileInfoMap() {
    if (fileInfoMap != null) {
      return;
    }
    fileInfoMap = new HashMap<>();
    for (FileInfo fileInfo : fileInfo) {
      fileInfoMap.put(fileInfo.getFileId(), fileInfo);
    }
  }

  public List<FileInfo> getFileInfo() {
    return fileInfo;
  }

  public List<LineInfo> getLineInfo() {
    return lineInfo;
  }

  public void addLineInfo(final LineInfo info) {
    lineInfo.add(info);
    lineInfo.sort(Comparator.comparingInt(LineInfo::getOutputStartLine));
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
