package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.probe.LogProbe;
import org.junit.jupiter.api.Test;

class ProbeMetadataTest {

  @Test
  void addProbe() {
    ProbeMetadata probeMetadata = new ProbeMetadata();
    probeMetadata.addProbe(LogProbe.builder().probeId("probe-id0", 0).build());
    probeMetadata.addProbe(LogProbe.builder().probeId("probe-id1", 0).build());
    probeMetadata.addProbe(LogProbe.builder().probeId("probe-id2", 0).build());
    assertEquals(3, probeMetadata.size());
    assertEquals("probe-id0", probeMetadata.getProbe(0).getId());
  }

  @Test
  void removeProbe() {
    ProbeMetadata probeMetadata = new ProbeMetadata();
    probeMetadata.addProbe(LogProbe.builder().probeId("probe-id0", 0).build());
    probeMetadata.addProbe(LogProbe.builder().probeId("probe-id1", 0).build());
    probeMetadata.addProbe(LogProbe.builder().probeId("probe-id2", 0).build());
    probeMetadata.removeProbe(1);
    assertEquals(2, probeMetadata.size());
    probeMetadata.removeProbe("probe-id2:0");
    assertEquals(1, probeMetadata.size());
    assertEquals("probe-id0", probeMetadata.getProbe(0).getId());
  }
}
