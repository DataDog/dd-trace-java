package com.datadog.debugger.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import utils.TestHelper;

class SnapshotSlicerTest {

  @Test
  public void noSlice() throws Exception {
    Assertions.assertEquals("[]", SnapshotSlicer.slice(16, "[]"));
    Assertions.assertEquals("{}", SnapshotSlicer.slice(16, "{}"));
    Assertions.assertEquals("[{},{},{}]", SnapshotSlicer.slice(16, "[{},{},{}]"));
    Assertions.assertEquals(
        "{\"foo\":[[],[],[]]}", SnapshotSlicer.slice(16, "{\"foo\":[[],[],[]]}"));
    Assertions.assertEquals("{\"foo\":\"bar\"}", SnapshotSlicer.slice(16, "{\"foo\":\"bar\"}"));
    Assertions.assertEquals("{\"foo\":1001}", SnapshotSlicer.slice(16, "{\"foo\":1001}"));
    Assertions.assertEquals("{\"foo\":3.14}", SnapshotSlicer.slice(16, "{\"foo\":3.14}"));
    Assertions.assertEquals("{\"foo\":true}", SnapshotSlicer.slice(16, "{\"foo\":true}"));
    Assertions.assertEquals(
        "{\"foo\":{\"name\":\"value\"}}",
        SnapshotSlicer.slice(16, "{\"foo\":{\"name\":\"value\"}}"));
    Assertions.assertEquals(
        "{\"foo1\":{\"foo2\":{\"foo3\":{\"foo4\":{\"foo5\":{}}}}}}",
        SnapshotSlicer.slice(16, "{\"foo1\":{\"foo2\":{\"foo3\":{\"foo4\":{\"foo5\":{}}}}}}"));
  }

  @Test
  public void sliceSmallSnapshot() throws Exception {
    String inputSmallSnapshot =
        TestHelper.getFixtureContent("/com/datadog/debugger/util/smallSnapshot.json").trim();
    Assertions.assertEquals(inputSmallSnapshot, SnapshotSlicer.slice(5, inputSmallSnapshot));
    String smallSnapshot4 =
        TestHelper.getFixtureContent("/com/datadog/debugger/util/smallSnapshot_4.json").trim();
    Assertions.assertEquals(smallSnapshot4, SnapshotSlicer.slice(4, inputSmallSnapshot));
    String smallSnapshot3 =
        TestHelper.getFixtureContent("/com/datadog/debugger/util/smallSnapshot_3.json").trim();
    Assertions.assertEquals(smallSnapshot3, SnapshotSlicer.slice(3, inputSmallSnapshot));
    String smallSnapshot2 =
        TestHelper.getFixtureContent("/com/datadog/debugger/util/smallSnapshot_2.json").trim();
    Assertions.assertEquals(smallSnapshot2, SnapshotSlicer.slice(2, inputSmallSnapshot));
    String smallSnapshot1 =
        TestHelper.getFixtureContent("/com/datadog/debugger/util/smallSnapshot_1.json").trim();
    Assertions.assertEquals(smallSnapshot1, SnapshotSlicer.slice(1, inputSmallSnapshot));
    String smallSnapshot0 =
        TestHelper.getFixtureContent("/com/datadog/debugger/util/smallSnapshot_0.json").trim();
    Assertions.assertEquals(smallSnapshot0, SnapshotSlicer.slice(0, inputSmallSnapshot));
  }

  @Test
  public void sliceLargeSnapshot() throws Exception {
    String inputLargeSnapshot =
        TestHelper.getFixtureContent("/com/datadog/debugger/util/largeSnapshot.json").trim();
    String largeSnapshot2 =
        TestHelper.getFixtureContent("/com/datadog/debugger/util/largeSnapshot_2.json").trim();
    Assertions.assertEquals(largeSnapshot2, SnapshotSlicer.slice(2, inputLargeSnapshot));
    String largeSnapshot1 =
        TestHelper.getFixtureContent("/com/datadog/debugger/util/largeSnapshot_1.json").trim();
    Assertions.assertEquals(largeSnapshot1, SnapshotSlicer.slice(1, inputLargeSnapshot));
    String largeSnapshot0 =
        TestHelper.getFixtureContent("/com/datadog/debugger/util/largeSnapshot_0.json").trim();
    Assertions.assertEquals(largeSnapshot0, SnapshotSlicer.slice(0, inputLargeSnapshot));
  }
}
