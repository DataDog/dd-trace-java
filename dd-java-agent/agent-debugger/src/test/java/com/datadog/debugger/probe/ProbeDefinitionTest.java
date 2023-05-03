package com.datadog.debugger.probe;

import datadog.trace.bootstrap.debugger.ProbeId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ProbeDefinitionTest {
  private static final ProbeId PROBE_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);

  @Test
  public void tags() {
    String[] tags = new String[] {"tag1:foo1", "tag2:foo2", "tag3"};
    ProbeDefinition snapshotProbe = LogProbe.builder().probeId(PROBE_ID).tags(tags).build();
    Assertions.assertEquals("foo1", snapshotProbe.getTagMap().get("tag1"));
    Assertions.assertEquals("foo2", snapshotProbe.getTagMap().get("tag2"));
    Assertions.assertNull(snapshotProbe.getTagMap().get("tag3"));
    Assertions.assertNotNull(snapshotProbe.getTags());
    Assertions.assertEquals(3, snapshotProbe.getTags().length);
    Assertions.assertEquals("tag1:foo1", snapshotProbe.getTags()[0].toString());
    Assertions.assertEquals("tag2:foo2", snapshotProbe.getTags()[1].toString());
    Assertions.assertEquals("tag3", snapshotProbe.getTags()[2].toString());
    Assertions.assertEquals("tag1:foo1,tag2:foo2,tag3", snapshotProbe.concatTags());
  }

  @Test
  public void noTags() {
    ProbeDefinition snapshotProbe = LogProbe.builder().probeId(PROBE_ID).build();
    Assertions.assertNull(snapshotProbe.getTags());
    Assertions.assertNull(snapshotProbe.concatTags());
  }

  @Test
  public void emptyTags() {
    ProbeDefinition snapshotProbe =
        LogProbe.builder().probeId(PROBE_ID).tags(new String[] {}).build();
    Assertions.assertEquals(0, snapshotProbe.getTags().length);
    Assertions.assertEquals("", snapshotProbe.concatTags());
  }
}
