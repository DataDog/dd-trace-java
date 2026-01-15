package datadog.trace.agent.tooling.iast.stratum;

import java.util.Iterator;
import java.util.List;

public class Resolver {
  public static SourceMap resolve(final SourceMap sourceMap) {
    for (EmbeddedStratum stratum : sourceMap.getEmbeddedStratumList()) {
      StratumExt outerStratum = sourceMap.getStratum(stratum.getName());
      if (outerStratum != null) {
        for (SourceMap embeddedSourceMap : stratum.getSourceMapList()) {
          SourceMap resolvedEmbeddedSourceMap = resolve(embeddedSourceMap);
          String outerFileName = resolvedEmbeddedSourceMap.getOutputFileName();
          for (StratumExt embeddedStratum : resolvedEmbeddedSourceMap.getStratumList()) {
            StratumExt resolvedStratum = sourceMap.getStratum(embeddedStratum.getName());
            if (resolvedStratum == null) {
              resolvedStratum = new StratumExt(embeddedStratum.getName());
              sourceMap.getStratumList().add(resolvedStratum);
            }
            resolve(new Context(outerStratum, outerFileName, resolvedStratum, embeddedStratum));
          }
        }
      }
    }
    sourceMap.getEmbeddedStratumList().clear();
    return sourceMap;
  }

  private static void resolve(final Context context) {
    for (LineInfo eli : context.embeddedStratum.getLineInfo()) {
      resolve(context, eli);
    }
  }

  private static void resolve(final Context context, final LineInfo eli) {
    Iterator<LineInfo> iter;
    if (eli.getRepeatCount() > 0) {
      for (iter = context.outerStratum.getLineInfo().iterator(); iter.hasNext(); ) {
        LineInfo oli = iter.next();
        if (oli.getFileInfo().getInputFileName().equals(context.outerFileName)) {
          if (oli.getInputStartLine() <= eli.getOutputStartLine()
              && eli.getOutputStartLine() < oli.getInputStartLine() + oli.getRepeatCount()) {
            int difference = eli.getOutputStartLine() - oli.getInputStartLine();
            int available = oli.getRepeatCount() - difference;
            int completeCount =
                Math.min(available / eli.getOutputLineIncrement(), eli.getRepeatCount());

            FileInfo fileInfo =
                getByPath(
                    context.resolvedStratum.getFileInfo(), eli.getFileInfo().getInputFilePath());
            if (fileInfo == null) {
              fileInfo = eli.getFileInfo();
              context.resolvedStratum.getFileInfo().add(fileInfo);
            }
            if (completeCount > 0) {
              LineInfo rli =
                  new LineInfo(
                      fileInfo,
                      eli.getInputStartLine(),
                      completeCount,
                      oli.getOutputStartLine() + difference * oli.getOutputLineIncrement(),
                      eli.getOutputLineIncrement() * oli.getOutputLineIncrement());

              context.resolvedStratum.addLineInfo(rli);
              LineInfo neli =
                  new LineInfo(
                      fileInfo,
                      eli.getInputStartLine() + completeCount,
                      eli.getRepeatCount() - completeCount,
                      eli.getOutputStartLine() + completeCount * eli.getOutputLineIncrement(),
                      eli.getOutputLineIncrement());

              resolve(context, neli);
            } else {
              LineInfo rli =
                  new LineInfo(
                      fileInfo,
                      eli.getInputStartLine(),
                      1,
                      oli.getOutputStartLine() + difference * oli.getOutputLineIncrement(),
                      available);

              context.resolvedStratum.addLineInfo(rli);
              LineInfo neli =
                  new LineInfo(
                      fileInfo,
                      eli.getInputStartLine(),
                      1,
                      eli.getOutputStartLine() + available,
                      eli.getOutputLineIncrement() - available);

              resolve(context, neli);
              neli =
                  new LineInfo(
                      fileInfo,
                      eli.getInputStartLine() + 1,
                      eli.getRepeatCount() - 1,
                      eli.getOutputStartLine() + eli.getOutputLineIncrement(),
                      eli.getOutputLineIncrement());

              resolve(context, neli);
            }
          }
        }
      }
    }
  }

  private static FileInfo getByPath(final List<FileInfo> list, final String filePath) {
    for (FileInfo fileInfo : list) {
      if (fileInfo.getInputFilePath().compareTo(filePath) == 0) {
        return fileInfo;
      }
    }
    return null;
  }

  private static class Context {

    StratumExt outerStratum;

    String outerFileName;

    StratumExt resolvedStratum;

    StratumExt embeddedStratum;

    public Context(
        final StratumExt outerStratum,
        final String outerFileName,
        final StratumExt resolvedStratum,
        final StratumExt embeddedStratum) {
      this.outerStratum = outerStratum;
      this.outerFileName = outerFileName;
      this.resolvedStratum = resolvedStratum;
      this.embeddedStratum = embeddedStratum;
    }
  }

  public Location resolve(final SourceMap sourceMap, final String stratumName, final int lineNum) {
    SourceMap resolvedSourceMap = resolve(sourceMap);
    StratumExt stratum = resolvedSourceMap.getStratum(stratumName);
    if (stratum == null) {
      return new Location(null, lineNum);
    }
    LineInfo bestFitLineInfo = null;
    int bestFitLineNum = lineNum;
    int bfOutputStartLine = Integer.MIN_VALUE;
    int bfOutputEndLine = Integer.MAX_VALUE;
    for (Iterator<LineInfo> iter = stratum.getLineInfo().iterator(); iter.hasNext(); ) {
      LineInfo lineInfo = iter.next();
      for (int i = 0; i < lineInfo.getRepeatCount(); i++) {
        int outputStartLine = lineInfo.getOutputStartLine() + i * lineInfo.getOutputLineIncrement();
        int outputEndLine =
            Math.max(outputStartLine, outputStartLine + lineInfo.getOutputLineIncrement() - 1);
        if (outputStartLine <= lineNum && lineNum <= outputEndLine) {
          if (lineInfo.getOutputLineIncrement() == 1) {
            return new Location(lineInfo.getFileInfo(), lineInfo.getInputStartLine() + i);
          }
          if (bfOutputStartLine <= outputStartLine && outputEndLine <= bfOutputEndLine) {
            bestFitLineInfo = lineInfo;
            bestFitLineNum = lineInfo.getInputStartLine() + i;
            bfOutputStartLine =
                bestFitLineInfo.getOutputStartLine() + i * bestFitLineInfo.getOutputLineIncrement();
            bfOutputEndLine =
                Math.max(
                    bfOutputStartLine,
                    bfOutputStartLine + bestFitLineInfo.getOutputLineIncrement() - 1);
          }
        }
      }
    }
    if (bestFitLineInfo != null) {
      return new Location(bestFitLineInfo.getFileInfo(), bestFitLineNum);
    }
    return new Location(null, lineNum);
  }
}
