package datadog.trace.agent.tooling.iast.stratum.parser;

import datadog.trace.agent.tooling.iast.stratum.EmbeddedStratum;
import datadog.trace.agent.tooling.iast.stratum.FileInfo;
import datadog.trace.agent.tooling.iast.stratum.LineInfo;
import datadog.trace.agent.tooling.iast.stratum.ParserException;
import datadog.trace.agent.tooling.iast.stratum.SourceMap;
import datadog.trace.agent.tooling.iast.stratum.SourceMapException;
import datadog.trace.agent.tooling.iast.stratum.StratumExt;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class Parser {
  private final Map<String, Builder> builders = new TreeMap<String, Builder>();

  private final State state = new State();

  public Parser() {
    registerBuilders();
  }

  protected void registerBuilders() {
    add(Builders.sourceMapBuilder());
    add(Builders.endSourceMapBuilder());
    add(Builders.stratumBuilder());
    add(Builders.fileInfoBuilder());
    add(Builders.lineInfoBuilder());
    add(Builders.vendorInfoBuilder());
    add(Builders.openEmbeddedStratumBuilder());
    add(Builders.closeStratumBuilder());
  }

  private void parseInit() throws SourceMapException {
    state.init();
  }

  private SourceMap[] parseDone() throws SourceMapException {
    EmbeddedStratum result = state.done();
    resolveLineFileInfo(result);
    return result.getSourceMapList().toArray(new SourceMap[0]);
  }

  private void resolveLineFileInfo(final EmbeddedStratum embeddedStratum)
      throws SourceMapException {
    for (Iterator<SourceMap> iter = embeddedStratum.getSourceMapList().iterator();
        iter.hasNext(); ) {
      SourceMap sourceMap = iter.next();
      resolveLineFileInfo(sourceMap);
    }
  }

  private void resolveLineFileInfo(final SourceMap sourceMap) throws SourceMapException {
    for (Iterator<StratumExt> iter = sourceMap.getStratumList().iterator(); iter.hasNext(); ) {
      StratumExt stratum = iter.next();
      resolveLineFileInfo(stratum);
    }
    for (Iterator<EmbeddedStratum> iter = sourceMap.getEmbeddedStratumList().iterator();
        iter.hasNext(); ) {
      EmbeddedStratum stratum = iter.next();
      resolveLineFileInfo(stratum);
    }
  }

  private void resolveLineFileInfo(final StratumExt stratum) throws SourceMapException {
    for (Iterator<LineInfo> iter = stratum.getLineInfo().iterator(); iter.hasNext(); ) {
      LineInfo lineInfo = iter.next();
      FileInfo fileInfo = get(stratum.getFileInfo(), lineInfo.getFileId());
      if (fileInfo == null) {
        throw new ParserException("Invalid file id: " + lineInfo.getFileId());
      }
      lineInfo.setFileInfo(fileInfo);
    }
  }

  public FileInfo get(final List<FileInfo> list, final int fileId) {
    for (FileInfo fileInfo : list) {
      if (fileInfo.getFileId() == fileId) {
        return fileInfo;
      }
    }
    return null;
  }

  private Builder getBuilder(final String[] lines) throws SourceMapException {
    if (lines.length == 0) {
      return null;
    }
    String sectionName = lines[0];
    String[] tokens = lines[0].split(" ", 2);
    if (tokens.length > 1) {
      sectionName = tokens[0].trim();
    }
    if (sectionName.startsWith("*")) {
      sectionName = sectionName.substring("*".length());
    }
    Builder builder = builders.get(sectionName);
    if (builder == null) {
      builder = Builders.unknownInfoBuilder();
    }
    return builder;
  }

  private void parseSection(final String[] lines) throws SourceMapException {
    Builder builder = getBuilder(lines);
    if (builder != null) {
      builder.build(state, lines);
    }
  }

  public SourceMap[] parse(final String source) throws SourceMapException, IOException {
    return parse(new StringReader(source));
  }

  public SourceMap[] parse(final Reader reader) throws SourceMapException, IOException {
    String line = "";
    try {
      parseInit();
      ArrayList<String> lines = new ArrayList<String>();
      BufferedReader br = new BufferedReader(reader);
      boolean sectionLine = true;
      while ((line = br.readLine()) != null) {
        state.lineNumber += 1;
        if (line.startsWith("*") || sectionLine && line.equals("SMAP")) {
          parseSection(lines.toArray(new String[0]));
          lines.clear();
        }
        sectionLine = line.startsWith("*");
        lines.add(line);
      }
      parseSection(lines.toArray(new String[0]));
      return parseDone();
    } catch (SourceMapException sme) {
      ParserException pe =
          new ParserException(sme.getMessage() + ":" + state.lineNumber + ":" + line);
      pe.initCause(sme);
      throw pe;
    }
  }

  public void add(final Builder builder) {
    builders.put(builder.getSectionName(), builder);
  }

  public void remove(final Builder builder) {
    builders.remove(builder.getSectionName());
  }
}
