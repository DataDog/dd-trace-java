package datadog.trace.agent.tooling.iast.stratum.parser;

import datadog.trace.agent.tooling.iast.stratum.EmbeddedStratum;
import datadog.trace.agent.tooling.iast.stratum.FileInfo;
import datadog.trace.agent.tooling.iast.stratum.LineInfo;
import datadog.trace.agent.tooling.iast.stratum.SourceMap;
import datadog.trace.agent.tooling.iast.stratum.SourceMapException;
import datadog.trace.agent.tooling.iast.stratum.StratumExt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A collection of builders to parse SMAP Information.
 * https://jakarta.ee/specifications/debugging/2.0/jdsol-spec-2.0#smap-syntax
 */
class Builders {

  private static final String LineInfoPattern =
      "(\\d++)(#(\\d++))?(,(\\d++))?:(\\d++)(,(\\d++))?($)";

  static final Pattern SPLITTER = Pattern.compile(" ");

  public static final Builder closeStratumBuilder() {
    return new Builder("C") {

      @Override
      public void build(final State state, final String[] lines) throws SourceMapException {
        String[] tokens = SPLITTER.split(lines[0], 2);
        if (tokens.length < 2 || tokens[1].equals("")) {
          throw new SourceMapException("Stratum name expected");
        }
        EmbeddedStratum embeddedStratum = new EmbeddedStratum(tokens[1]);
        state.pop(embeddedStratum);
      }
    };
  }

  public static final Builder endSourceMapBuilder() {
    return new Builder("E") {

      @Override
      public void build(final State state, final String[] lines) throws SourceMapException {
        state.endSourceMap();
      }
    };
  }

  public static final Builder fileInfoBuilder() {
    return new Builder("F") {

      @Override
      public void build(final State state, final String[] lines) throws SourceMapException {
        if (!state.getStratum().getFileInfo().isEmpty()) {
          throw new SourceMapException("Only one file section allowed");
        }
        for (int i = 1; i < lines.length; ) {
          FileInfo fileInfo = new FileInfo();
          String s = lines[i++];
          String fileId = "0";
          String fileName = "";
          String filePath = "";
          if (s.startsWith("+")) {
            String[] tokens = SPLITTER.split(s, 3);
            fileId = tokens[1];
            fileName = tokens[2];
            if (i == lines.length) {
              throw new SourceMapException("File path expected");
            }
            filePath = lines[i++];
          } else {
            String[] tokens = SPLITTER.split(s, 2);
            fileId = tokens[0];
            fileName = tokens[1];
            filePath = fileName;
          }
          try {
            fileInfo.setFileId(Integer.parseInt(fileId));
          } catch (NumberFormatException nfe) {
            throw new SourceMapException("Invalid file id: " + fileId);
          }
          fileInfo.setInputFileName(fileName);
          fileInfo.setInputFilePath(filePath);
          state.getStratum().getFileInfo().add(fileInfo);
        }
      }
    };
  }

  public static Builder lineInfoBuilder() {
    return new Builder("L") {

      @Override
      public void build(final State state, final String[] lines) throws SourceMapException {
        if (!state.getStratum().getLineInfo().isEmpty()) {
          throw new SourceMapException("Only one line section allowed");
        }
        Pattern p = Pattern.compile(LineInfoPattern);
        int fileId = 0;
        for (int i = 1; i < lines.length; i++) {
          int inputStartLine = 1;
          int repeatCount = 1;
          int outputStartLine = 1;
          int outputLineIncrement = 1;
          Matcher m = p.matcher(lines[i]);
          if (!m.matches()) {
            throw new SourceMapException("Invalid line info: " + lines[i]);
          }
          try {
            inputStartLine = Integer.parseInt(m.group(1));
            if (m.group(3) != null) {
              fileId = Integer.parseInt(m.group(3));
            }
            if (m.group(5) != null) {
              repeatCount = Integer.parseInt(m.group(5));
            }
            outputStartLine = Integer.parseInt(m.group(6));
            if (m.group(8) != null) {
              outputLineIncrement = Integer.parseInt(m.group(8));
            }
          } catch (NumberFormatException nfe) {
            throw new SourceMapException("Invalid line info: " + lines[i]);
          }
          LineInfo lineInfo =
              new LineInfo(
                  fileId, inputStartLine, repeatCount, outputStartLine, outputLineIncrement);
          state.getStratum().addLineInfo(lineInfo);
        }
      }
    };
  }

  public static Builder sourceMapBuilder() {
    return new Builder("SMAP") {
      @Override
      public void build(final State state, final String[] lines) throws SourceMapException {
        if (lines.length < 3) {
          throw new SourceMapException("Source map information expected");
        }
        SourceMap sourceMap = new SourceMap(lines[1], lines[2]);
        state.getParentStratum().getSourceMapList().add(sourceMap);
        state.setSourceMap(sourceMap);
      }
    };
  }

  public static Builder openEmbeddedStratumBuilder() {
    return new Builder("O") {
      @Override
      public void build(final State state, final String[] lines) throws SourceMapException {
        String[] tokens = SPLITTER.split(lines[0], 2);
        if (tokens.length < 2 || tokens[1].equals("")) {
          throw new SourceMapException("Stratum name expected");
        }
        EmbeddedStratum embeddedStratum = new EmbeddedStratum(tokens[1]);
        state.getSourceMap().getEmbeddedStratumList().add(embeddedStratum);
        state.push(embeddedStratum);
      }
    };
  }

  public static Builder stratumBuilder() {
    return new Builder("S") {
      @Override
      public void build(final State state, final String[] lines) throws SourceMapException {
        String[] tokens = SPLITTER.split(lines[0], 2);
        if (tokens.length < 2 || tokens[1].equals("")) {
          throw new SourceMapException("Stratum name expected");
        }
        StratumExt stratum = new StratumExt(tokens[1]);
        state.getSourceMap().getStratumList().add(stratum);
        state.setStratum(stratum);
      }
    };
  }
}
