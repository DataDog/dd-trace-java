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
        StratumExt stratum = sourceMap.getStratum("KotlinDebug");
        if (stratum == null) {
          throw new IllegalArgumentException("No stratum found for KotlinDebug");
        }
        return new KotlinSourceRemapper(stratum);
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
    private final StratumExt stratum;

    public KotlinSourceRemapper(StratumExt stratum) {
      this.stratum = stratum;
    }

    @Override
    public int remapSourceLine(int line) {
      Pair<String, Integer> pair = stratum.getInputLine(line);
      if (pair == null || pair.getRight() == null) {
        return line;
      }
      return pair.getRight();
    }
  }
}
