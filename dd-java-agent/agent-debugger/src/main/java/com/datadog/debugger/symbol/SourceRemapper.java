package com.datadog.debugger.symbol;

import com.datadog.debugger.util.JvmLanguage;
import datadog.trace.agent.tooling.stratum.SourceMap;
import datadog.trace.agent.tooling.stratum.StratumExt;
import datadog.trace.api.Pair;

public interface SourceRemapper {

  int remapSourceLine(int line);

  static SourceRemapper getSourceRemapper(String sourceFile, SourceMap sourceMap) {
    JvmLanguage jvmLanguage = JvmLanguage.of(sourceFile);
    switch (jvmLanguage) {
      case KOTLIN:
        StratumExt stratumMain = sourceMap.getStratum("Kotlin");
        if (stratumMain == null) {
          stratumMain = sourceMap.getStratum(sourceMap.getDefaultStratumName());
          if (stratumMain == null) {
            throw new IllegalArgumentException("No default stratum found");
          }
        }
        StratumExt stratumDebug = sourceMap.getStratum("KotlinDebug");
        if (stratumDebug == null) {
          throw new IllegalArgumentException("No stratumDebug found for KotlinDebug");
        }
        return new KotlinSourceRemapper(stratumMain, stratumDebug);
      default:
        return NOOP_REMAPPER;
    }
  }

  SourceRemapper NOOP_REMAPPER = new NoopSourceRemapper();

  class NoopSourceRemapper implements SourceRemapper {
    @Override
    public int remapSourceLine(int line) {
      return line;
    }
  }

  class KotlinSourceRemapper implements SourceRemapper {
    private final StratumExt stratumMain;
    private final StratumExt stratumDebug;

    public KotlinSourceRemapper(StratumExt stratumMain, StratumExt stratumDebug) {
      this.stratumMain = stratumMain;
      this.stratumDebug = stratumDebug;
    }

    @Override
    public int remapSourceLine(int line) {
      Pair<String, Integer> pairDebug = stratumDebug.getInputLine(line);
      if (pairDebug == null || pairDebug.getRight() == null) {
        Pair<String, Integer> pairMain = stratumMain.getInputLine(line);
        if (pairMain == null || pairMain.getRight() == null) {
          return line;
        }
        String fileId = pairMain.getLeft();
        String sourceFileName = stratumMain.getSourceFileName(fileId);
        if (sourceFileName == null) {
          throw new IllegalArgumentException("Cannot find source filename for fileid=" + fileId);
        }
        if (sourceFileName.equals("fake.kt")) {
          return -1; // no mapping possible
        }
        return pairMain.getRight();
      }
      return pairDebug.getRight();
    }
  }
}
