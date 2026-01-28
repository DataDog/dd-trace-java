package com.datadog.debugger.symbol;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.agent.tooling.stratum.SourceMap;
import datadog.trace.agent.tooling.stratum.StratumExt;
import datadog.trace.api.Pair;
import org.junit.jupiter.api.Test;

class SourceRemapperTest {

  @Test
  public void noopSourceRemapper() {
    assertEquals(SourceRemapper.NOOP_REMAPPER, SourceRemapper.getSourceRemapper("foo.java", null));
    assertEquals(SourceRemapper.NOOP_REMAPPER, SourceRemapper.getSourceRemapper("foo.dat", null));
  }

  @Test
  public void kotlinSourceRemapper() {
    SourceMap sourceMapMock = mock(SourceMap.class);
    StratumExt stratumMock = mock(StratumExt.class);
    when(sourceMapMock.getStratum(eq("KotlinDebug"))).thenReturn(stratumMock);
    when(stratumMock.getInputLine(eq(42))).thenReturn(Pair.of("", 24));
    SourceRemapper sourceRemapper = SourceRemapper.getSourceRemapper("foo.kt", sourceMapMock);
    assertTrue(sourceRemapper instanceof SourceRemapper.KotlinSourceRemapper);
    assertEquals(24, sourceRemapper.remapSourceLine(42));
  }
}
