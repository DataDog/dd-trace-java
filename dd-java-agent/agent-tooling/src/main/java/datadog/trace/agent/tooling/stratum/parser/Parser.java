package datadog.trace.agent.tooling.stratum.parser;

import static datadog.trace.agent.tooling.stratum.parser.Builder.SPACE_PATTERN;

import datadog.trace.agent.tooling.stratum.EmbeddedStratum;
import datadog.trace.agent.tooling.stratum.FileInfo;
import datadog.trace.agent.tooling.stratum.LineInfo;
import datadog.trace.agent.tooling.stratum.SourceMap;
import datadog.trace.agent.tooling.stratum.StratumExt;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class Parser {
  private static final Pattern END_OF_LINE_PATTERN = Pattern.compile("\n");

  private static List<SourceMap> parseDone(State state) {
    EmbeddedStratum result = state.done();
    resolveLineFileInfo(result);
    return result.getSourceMapList();
  }

  private static void resolveLineFileInfo(final EmbeddedStratum embeddedStratum) {
    for (SourceMap sourceMap : embeddedStratum.getSourceMapList()) {
      resolveLineFileInfo(sourceMap);
    }
  }

  private static void resolveLineFileInfo(final SourceMap sourceMap) {
    for (StratumExt stratum : sourceMap.getStratumList()) {
      resolveLineFileInfo(stratum);
    }
    for (EmbeddedStratum stratum : sourceMap.getEmbeddedStratumList()) {
      resolveLineFileInfo(stratum);
    }
  }

  private static void resolveLineFileInfo(final StratumExt stratum) {
    for (LineInfo lineInfo : stratum.getLineInfo()) {
      FileInfo fileInfo = get(stratum.getFileInfo(), lineInfo.getFileId());
      if (fileInfo == null) {
        throw new IllegalArgumentException("Invalid file id: " + lineInfo.getFileId());
      }
      lineInfo.setFileInfo(fileInfo);
    }
  }

  public static FileInfo get(final List<FileInfo> list, String fileId) {
    for (FileInfo fileInfo : list) {
      if (fileInfo.getFileId().equals(fileId)) {
        return fileInfo;
      }
    }
    return null;
  }

  private static Builder getBuilder(List<String> lines) {
    if (lines.isEmpty()) {
      return null;
    }
    String sectionName = lines.get(0);
    String[] tokens = SPACE_PATTERN.split(lines.get(0), 2);
    if (tokens.length > 1) {
      sectionName = tokens[0].trim();
    }
    if (sectionName.startsWith("*")) {
      sectionName = sectionName.substring("*".length());
    }
    return Builder.get(sectionName);
  }

  private static void parseSection(State state, List<String> lines) {
    Builder builder = getBuilder(lines);
    if (builder != null) {
      builder.build(state, lines);
    }
  }

  public static List<SourceMap> parse(String source) {
    State state = new State();
    String line = null;
    try {
      String[] lines = END_OF_LINE_PATTERN.split(source);
      boolean isSectionLine = true;
      List<String> sectionLines = new ArrayList<>();
      for (int i = 0; i < lines.length; i++) {
        line = lines[i];
        state.lineNumber += 1;
        if (line.startsWith("*") || isSectionLine && line.equals("SMAP")) {
          parseSection(state, sectionLines);
          sectionLines.clear();
        }
        isSectionLine = line.startsWith("*");
        sectionLines.add(line);
      }
      parseSection(state, sectionLines);
      return parseDone(state);
    } catch (Exception sme) {
      throw new RuntimeException(sme.getMessage() + ":" + state.lineNumber + ":" + line, sme);
    }
  }
}
