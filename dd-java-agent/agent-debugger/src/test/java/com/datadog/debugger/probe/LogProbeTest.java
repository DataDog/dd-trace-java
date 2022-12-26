package com.datadog.debugger.probe;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class LogProbeTest {
  private static final String LANGUAGE = "java";
  private static final String PROBE_ID = "beae1807-f3b0-4ea8-a74f-826790c5e6f8";

  @Test
  public void testCapture() {
    LogProbe.Builder builder = createLog();
    LogProbe snapshotProbe = builder.capture(1, 420, 255, 20).build();
    Assert.assertEquals(1, snapshotProbe.getCapture().getMaxReferenceDepth());
    Assert.assertEquals(420, snapshotProbe.getCapture().getMaxCollectionSize());
    Assert.assertEquals(255, snapshotProbe.getCapture().getMaxLength());
  }

  @Test
  public void testSampling() {
    LogProbe.Builder builder = createLog();
    LogProbe snapshotProbe = builder.sampling(0.25).build();
    Assert.assertEquals(0.25, snapshotProbe.getSampling().getSnapshotsPerSecond(), 0.01);
  }

  @Test
  public void log() {
    LogProbe logProbe = createLog().template(null).build();
    assertNull(logProbe.getTemplate());
    assertTrue(logProbe.getSegments().isEmpty());
    logProbe = createLog().template("plain log line").build();
    assertEquals("plain log line", logProbe.getTemplate());
    assertEquals(1, logProbe.getSegments().size());
    assertEquals("plain log line", logProbe.getSegments().get(0).getStr());
    assertNull(logProbe.getSegments().get(0).getExpr());
    assertNull(logProbe.getSegments().get(0).getParsedExpr());
    logProbe = createLog().template("simple template log line {arg}").build();
    assertEquals("simple template log line {arg}", logProbe.getTemplate());
    assertEquals(2, logProbe.getSegments().size());
    assertEquals("simple template log line ", logProbe.getSegments().get(0).getStr());
    assertEquals("arg", logProbe.getSegments().get(1).getExpr());
    logProbe = createLog().template("{arg1}={arg2} {{{count(array)}}}").build();
    assertEquals("{arg1}={arg2} {{{count(array)}}}", logProbe.getTemplate());
    assertEquals(6, logProbe.getSegments().size());
    assertEquals("arg1", logProbe.getSegments().get(0).getExpr());
    assertEquals("=", logProbe.getSegments().get(1).getStr());
    assertEquals("arg2", logProbe.getSegments().get(2).getExpr());
    assertEquals(" {", logProbe.getSegments().get(3).getStr());
    assertEquals("count(array)", logProbe.getSegments().get(4).getExpr());
    assertEquals("}", logProbe.getSegments().get(5).getStr());
  }

  private LogProbe.Builder createLog() {
    return LogProbe.builder().language(LANGUAGE).probeId(PROBE_ID);
  }
}
