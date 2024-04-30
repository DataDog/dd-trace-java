package datadog.trace.agent.tooling.iast.stratum;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StratumExt extends AbstractStratum implements Cloneable, Stratum {
  private final List<FileInfo> fileInfo = new ArrayList<FileInfo>();

  private int[] lineStart = null;

  private final List<LineInfo> lineInfo = new ArrayList<LineInfo>();

  private final List<VendorInfo> vendorInfo = new ArrayList<VendorInfo>();

  private final List<UnknownInfo> unknownInfo = new ArrayList<UnknownInfo>();

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
  public Object clone() {
    StratumExt stratum = new StratumExt(getName());
    for (Iterator<VendorInfo> iter = vendorInfo.iterator(); iter.hasNext(); ) {
      stratum.getVendorInfo().add((VendorInfo) iter.next().clone());
    }
    for (Iterator<UnknownInfo> iter = unknownInfo.iterator(); iter.hasNext(); ) {
      stratum.getUnknownInfo().add((UnknownInfo) iter.next().clone());
    }
    Map<FileInfo, FileInfo> fileInfoMap = new HashMap<FileInfo, FileInfo>();
    for (Iterator<FileInfo> iter = fileInfo.iterator(); iter.hasNext(); ) {
      FileInfo fileInfoOrig = iter.next();
      FileInfo fileInfoClone = (FileInfo) fileInfoOrig.clone();
      fileInfoMap.put(fileInfoOrig, fileInfoClone);
      stratum.getFileInfo().add(fileInfoClone);
    }

    for (Iterator<LineInfo> iter = lineInfo.iterator(); iter.hasNext(); ) {
      LineInfo lineInfo = iter.next();
      FileInfo fileInfo = lineInfo.getFileInfo();
      if (fileInfo != null) {
        fileInfo = fileInfoMap.get(fileInfo);
        lineInfo.setFileInfo(fileInfo);
      }
      stratum.addLineInfo(lineInfo);
    }

    return stratum;
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

  public List<VendorInfo> getVendorInfo() {
    return vendorInfo;
  }

  public void setVendorInfo(final List<VendorInfo> vendorInfoList) {
    vendorInfo.clear();
    if (vendorInfoList != null) {
      vendorInfo.addAll(vendorInfoList);
    }
  }

  public List<UnknownInfo> getUnknownInfo() {
    return unknownInfo;
  }

  public void setUnknownInfo(final List<UnknownInfo> unknownInfoList) {
    unknownInfo.clear();
    if (unknownInfoList != null) {
      unknownInfo.addAll(unknownInfoList);
    }
  }

  @Override
  public String toString() {
    return "Stratum [fileInfoList="
        + fileInfo
        + ", lineInfoList="
        + lineInfo
        + ", vendorInfoList="
        + vendorInfo
        + ", unknownInfoList="
        + unknownInfo
        + "]";
  }
}
