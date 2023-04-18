package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ValueScriptHelperTest {

  private static final int PROBE_VERSION = 42;
  private static final ProbeId PROBE_ID = new ProbeId("12fd-8490-c111-4374-ffde", PROBE_VERSION);
  private static final ProbeLocation PROBE_LOCATION =
      new ProbeLocation("java.lang.String", "indexOf", "String.java", Arrays.asList("12-15", "23"));
  private static final ProbeImplementation DUMMY_PROBE =
      new ProbeImplementation.NoopProbeImplementation(PROBE_ID, PROBE_LOCATION);

  @Test
  public void nullValue() {
    CapturedContext.Status status = new CapturedContext.Status(DUMMY_PROBE);
    StringBuilder sb = new StringBuilder();
    ValueScriptHelper.serializeValue(sb, "", null, status);
    assertEquals("null", sb.toString());
  }

  @Test
  public void basicString() {
    CapturedContext.Status status = new CapturedContext.Status(DUMMY_PROBE);
    StringBuilder sb = new StringBuilder();
    ValueScriptHelper.serializeValue(sb, "", "foo", status);
    assertEquals("foo", sb.toString());
  }
}
