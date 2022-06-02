package com.datadog.debugger.agent;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class SnapshotProbeTest {

  private static final String LANGUAGE = "java";
  private static final String PROBE_ID = "beae1807-f3b0-4ea8-a74f-826790c5e6f8";

  @Test
  public void tags() {
    String[] tags = new String[] {"tag1:foo1", "tag2:foo2", "tag3"};
    SnapshotProbe snapshotProbe =
        new SnapshotProbe(LANGUAGE, PROBE_ID, true, tags, null, null, null, null);
    Assert.assertEquals("foo1", snapshotProbe.getTagMap().get("tag1"));
    Assert.assertEquals("foo2", snapshotProbe.getTagMap().get("tag2"));
    Assert.assertNull(snapshotProbe.getTagMap().get("tag3"));
    Assert.assertNotNull(snapshotProbe.getTags());
    Assert.assertEquals(3, snapshotProbe.getTags().length);
    Assert.assertEquals("tag1:foo1", snapshotProbe.getTags()[0].toString());
    Assert.assertEquals("tag2:foo2", snapshotProbe.getTags()[1].toString());
    Assert.assertEquals("tag3", snapshotProbe.getTags()[2].toString());
    Assert.assertEquals("tag1:foo1,tag2:foo2,tag3", snapshotProbe.concatTags());
  }

  @Test
  public void noTags() {
    SnapshotProbe snapshotProbe =
        new SnapshotProbe(LANGUAGE, PROBE_ID, true, null, null, null, null, null);
    Assert.assertNull(snapshotProbe.getTags());
    Assert.assertNull(snapshotProbe.concatTags());
  }

  @Test
  public void emptyTags() {
    SnapshotProbe snapshotProbe =
        new SnapshotProbe(LANGUAGE, PROBE_ID, true, new String[] {}, null, null, null, null);
    Assert.assertEquals(0, snapshotProbe.getTags().length);
    Assert.assertEquals("", snapshotProbe.concatTags());
  }

  @Test
  public void testCapture() {
    SnapshotProbe.Builder builder = createProbe();
    SnapshotProbe snapshotProbe = builder.capture(1, 420, 255, 1, 20).build();
    Assert.assertEquals(1, snapshotProbe.getCapture().getMaxReferenceDepth());
    Assert.assertEquals(420, snapshotProbe.getCapture().getMaxCollectionSize());
    Assert.assertEquals(255, snapshotProbe.getCapture().getMaxLength());
    Assert.assertEquals(1, snapshotProbe.getCapture().getMaxFieldDepth());
  }

  @Test
  public void testSampling() {
    SnapshotProbe.Builder builder = createProbe();
    SnapshotProbe snapshotProbe = builder.sampling(0.25).build();
    Assert.assertEquals(0.25, snapshotProbe.getSampling().getSnapshotsPerSecond(), 0.01);
  }

  private static SnapshotProbe.Builder createProbe() {
    return SnapshotProbe.builder().language(LANGUAGE).probeId(PROBE_ID).active(true);
  }
}
