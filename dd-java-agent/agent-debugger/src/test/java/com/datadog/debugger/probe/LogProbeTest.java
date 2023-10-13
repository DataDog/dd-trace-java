package com.datadog.debugger.probe;

import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.debugger.ProbeId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LogProbeTest {
  private static final String LANGUAGE = "java";
  private static final ProbeId PROBE_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);

  @Test
  public void testCapture() {
    LogProbe.Builder builder = createLog(null);
    LogProbe snapshotProbe = builder.capture(1, 420, 255, 20).build();
    Assertions.assertEquals(1, snapshotProbe.getCapture().getMaxReferenceDepth());
    Assertions.assertEquals(420, snapshotProbe.getCapture().getMaxCollectionSize());
    Assertions.assertEquals(255, snapshotProbe.getCapture().getMaxLength());
  }

  @Test
  public void testSampling() {
    LogProbe.Builder builder = createLog(null);
    LogProbe snapshotProbe = builder.sampling(0.25).build();
    Assertions.assertEquals(0.25, snapshotProbe.getSampling().getSnapshotsPerSecond(), 0.01);
  }

  @Test
  public void log() {
    LogProbe logProbe = createLog(null).build();
    assertNull(logProbe.getTemplate());
    assertTrue(logProbe.getSegments().isEmpty());
    logProbe = createLog("plain log line").build();
    assertEquals("plain log line", logProbe.getTemplate());
    assertEquals(1, logProbe.getSegments().size());
    assertEquals("plain log line", logProbe.getSegments().get(0).getStr());
    assertNull(logProbe.getSegments().get(0).getExpr());
    assertNull(logProbe.getSegments().get(0).getParsedExpr());
    logProbe = createLog("simple template log line {arg}").build();
    assertEquals("simple template log line {arg}", logProbe.getTemplate());
    assertEquals(2, logProbe.getSegments().size());
    assertEquals("simple template log line ", logProbe.getSegments().get(0).getStr());
    assertEquals("arg", logProbe.getSegments().get(1).getExpr());
    logProbe = createLog("{arg1}={arg2} {{{count(array)}}}").build();
    assertEquals("{arg1}={arg2} {{{count(array)}}}", logProbe.getTemplate());
    assertEquals(6, logProbe.getSegments().size());
    assertEquals("arg1", logProbe.getSegments().get(0).getExpr());
    assertEquals("=", logProbe.getSegments().get(1).getStr());
    assertEquals("arg2", logProbe.getSegments().get(2).getExpr());
    assertEquals(" {", logProbe.getSegments().get(3).getStr());
    assertEquals("count(array)", logProbe.getSegments().get(4).getExpr());
    assertEquals("}", logProbe.getSegments().get(5).getStr());
  }

  private LogProbe.Builder createLog(String template) {
    return LogProbe.builder()
        .language(LANGUAGE)
        .probeId(PROBE_ID)
        .template(template, parseTemplate(template));
  }
}
