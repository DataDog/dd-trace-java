package com.datadog.debugger.probe;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class ProbeDefinitionTest {
  private static final String PROBE_ID = "beae1807-f3b0-4ea8-a74f-826790c5e6f8";

  @Test
  public void tags() {
    String[] tags = new String[] {"tag1:foo1", "tag2:foo2", "tag3"};
    ProbeDefinition snapshotProbe = LogProbe.builder().probeId(PROBE_ID).tags(tags).build();
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
    ProbeDefinition snapshotProbe = LogProbe.builder().probeId(PROBE_ID).build();
    Assert.assertNull(snapshotProbe.getTags());
    Assert.assertNull(snapshotProbe.concatTags());
  }

  @Test
  public void emptyTags() {
    ProbeDefinition snapshotProbe =
        LogProbe.builder().probeId(PROBE_ID).tags(new String[] {}).build();
    Assert.assertEquals(0, snapshotProbe.getTags().length);
    Assert.assertEquals("", snapshotProbe.concatTags());
  }
}
