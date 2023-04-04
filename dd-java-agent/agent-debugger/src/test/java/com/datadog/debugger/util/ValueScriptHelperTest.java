package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.bootstrap.debugger.Snapshot;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ValueScriptHelperTest {

  private static final String PROBE_ID = "12fd-8490-c111-4374-ffde";
  private static final int PROBE_VERSION = 42;
  private static final Snapshot.ProbeLocation PROBE_LOCATION =
      new Snapshot.ProbeLocation(
          "java.lang.String", "indexOf", "String.java", Arrays.asList("12-15", "23"));
  private static final Snapshot.ProbeDetails DUMMY_PROBE =
      new Snapshot.ProbeDetails.DummyProbe(PROBE_ID, PROBE_LOCATION);

  @Test
  public void nullValue() {
    Snapshot.CapturedContext.Status status = new Snapshot.CapturedContext.Status(DUMMY_PROBE);
    StringBuilder sb = new StringBuilder();
    ValueScriptHelper.serializeValue(sb, "", null, status);
    assertEquals("null", sb.toString());
  }

  @Test
  public void basicString() {
    Snapshot.CapturedContext.Status status = new Snapshot.CapturedContext.Status(DUMMY_PROBE);
    StringBuilder sb = new StringBuilder();
    ValueScriptHelper.serializeValue(sb, "", "foo", status);
    assertEquals("foo", sb.toString());
  }
}
