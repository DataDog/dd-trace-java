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
    StratumExt stratumMainMock = mock(StratumExt.class);
    StratumExt stratumDebugMock = mock(StratumExt.class);
    when(sourceMapMock.getStratum(eq("Kotlin"))).thenReturn(stratumMainMock);
    when(sourceMapMock.getStratum(eq("KotlinDebug"))).thenReturn(stratumDebugMock);
    when(stratumDebugMock.getInputLine(eq(42))).thenReturn(Pair.of("", 24));
    SourceRemapper sourceRemapper = SourceRemapper.getSourceRemapper("foo.kt", sourceMapMock);
    assertTrue(sourceRemapper instanceof SourceRemapper.KotlinSourceRemapper);
    assertEquals(24, sourceRemapper.remapSourceLine(42));
  }

  @Test
  public void noKotlinDebug() {
    SourceMap sourceMapMock = mock(SourceMap.class);
    when(sourceMapMock.getDefaultStratumName()).thenReturn("Main");
    StratumExt stratumMainMock = mock(StratumExt.class);
    when(sourceMapMock.getStratum(eq("Kotlin"))).thenReturn(stratumMainMock);
    when(sourceMapMock.getStratum(eq("KotlinDebug"))).thenReturn(null);
    IllegalArgumentException illegalArgumentException =
        assertThrows(
            IllegalArgumentException.class,
            () -> SourceRemapper.getSourceRemapper("foo.kt", sourceMapMock));
    assertEquals("No stratumDebug found for KotlinDebug", illegalArgumentException.getMessage());
  }
}
