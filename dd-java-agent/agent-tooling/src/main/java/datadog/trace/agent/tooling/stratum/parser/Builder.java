package datadog.trace.agent.tooling.stratum.parser;

import datadog.trace.agent.tooling.stratum.EmbeddedStratum;
import datadog.trace.agent.tooling.stratum.FileInfo;
import datadog.trace.agent.tooling.stratum.LineInfo;
import datadog.trace.agent.tooling.stratum.SourceMap;
import datadog.trace.agent.tooling.stratum.StratumExt;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A collection of builders to parse SMAP Information.
 * https://jakarta.ee/specifications/debugging/2.0/jdsol-spec-2.0#smap-syntax
 */
enum Builder {
  CLOSE_STRATUM {
    @Override
    public void build(State state, List<String> lines) {
      String[] tokens = SPACE_PATTERN.split(lines.get(0), 2);
      if (tokens.length < 2 || tokens[1].equals("")) {
        throw new IllegalArgumentException("Stratum name expected");
      }
      EmbeddedStratum embeddedStratum = new EmbeddedStratum(tokens[1]);
      state.pop(embeddedStratum);
    }
  },
  END_SOURCE {
    @Override
    public void build(final State state, final List<String> lines) {
      state.endSourceMap();
    }
  },
  FILE_INFO {
    @Override
    public void build(final State state, final List<String> lines) {
      if (!state.getStratum().getFileInfo().isEmpty()) {
        throw new IllegalArgumentException("Only one file section allowed");
      }
      for (int i = 1; i < lines.size(); ) {
        String s = lines.get(i++);
        String fileId;
        String fileName;
        String filePath;
        if (s.startsWith("+")) {
          String[] tokens = SPACE_PATTERN.split(s, 3);
          fileId = tokens[1];
          fileName = tokens[2];
          if (i == lines.size()) {
            throw new IllegalStateException("File path expected");
          }
          filePath = lines.get(i++);
        } else {
          String[] tokens = SPACE_PATTERN.split(s, 2);
          fileId = tokens[0];
          fileName = tokens[1];
          filePath = fileName;
        }
        state.getStratum().getFileInfo().add(new FileInfo(fileId, fileName, filePath));
      }
    }
  },
  LINE_INFO {
    @Override
    public void build(final State state, final List<String> lines) {
      if (!state.getStratum().getLineInfo().isEmpty()) {
        throw new IllegalStateException("Only one line section allowed");
      }
      String fileId = "0"; // spec says default is zero
      for (int i = 1; i < lines.size(); i++) {
        int inputStartLine = 1;
        int repeatCount = 1;
        int outputStartLine = 1;
        int outputLineIncrement = 1;
        Matcher m = LINE_INFO_PATTERN.matcher(lines.get(i));
        if (!m.matches()) {
          throw new IllegalArgumentException("Invalid line info: " + lines.get(i));
        }
        try {
          inputStartLine = Integer.parseInt(m.group(1));
          if (m.group(3) != null) {
            fileId = m.group(3);
          }
          if (m.group(5) != null) {
            repeatCount = Integer.parseInt(m.group(5));
          }
          outputStartLine = Integer.parseInt(m.group(6));
          if (m.group(8) != null) {
            outputLineIncrement = Integer.parseInt(m.group(8));
          }
        } catch (NumberFormatException nfe) {
          throw new IllegalArgumentException("Invalid line info: " + lines.get(i));
        }
        LineInfo lineInfo =
            new LineInfo(fileId, inputStartLine, repeatCount, outputStartLine, outputLineIncrement);
        state.getStratum().addLineInfo(lineInfo);
      }
    }
  },
  SOURCE_MAP {
    @Override
    public void build(final State state, final List<String> lines) {
      if (lines.size() < 3) {
        throw new IllegalStateException("Source map information expected");
      }
      SourceMap sourceMap = new SourceMap(lines.get(1), lines.get(2));
      state.getParentStratum().getSourceMapList().add(sourceMap);
      state.setSourceMap(sourceMap);
    }
  },
  OPEN_EMBEDDED_STRATUM {
    @Override
    public void build(final State state, final List<String> lines) {
      String[] tokens = SPACE_PATTERN.split(lines.get(0), 2);
      if (tokens.length < 2 || tokens[1].equals("")) {
        throw new IllegalStateException("Stratum name expected");
      }
      EmbeddedStratum embeddedStratum = new EmbeddedStratum(tokens[1]);
      state.getSourceMap().getEmbeddedStratumList().add(embeddedStratum);
      state.push(embeddedStratum);
    }
  },
  STRATUM {
    @Override
    public void build(final State state, final List<String> lines) {
      String[] tokens = SPACE_PATTERN.split(lines.get(0), 2);
      if (tokens.length < 2 || tokens[1].isEmpty()) {
        throw new IllegalStateException("Stratum name expected");
      }
      StratumExt stratum = new StratumExt(tokens[1]);
      state.getSourceMap().getStratumList().add(stratum);
      state.setStratum(stratum);
    }
  };

  private static final Pattern LINE_INFO_PATTERN =
      Pattern.compile("(\\d++)(#(\\d++))?(,(\\d++))?:(\\d++)(,(\\d++))?($)");

  static final Pattern SPACE_PATTERN = Pattern.compile(" ");

  abstract void build(State paramState, List<String> lines);

  public static Builder get(String sectionName) {
    switch (sectionName) {
      case "C":
        return CLOSE_STRATUM;
      case "E":
        return END_SOURCE;
      case "F":
        return FILE_INFO;
      case "L":
        return LINE_INFO;
      case "SMAP":
        return SOURCE_MAP;
      case "O":
        return OPEN_EMBEDDED_STRATUM;
      case "S":
        return STRATUM;
      default:
        throw new IllegalArgumentException("Invalid section name: " + sectionName);
    }
  }
}
