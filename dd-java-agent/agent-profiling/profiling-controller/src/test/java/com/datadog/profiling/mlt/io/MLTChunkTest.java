package com.datadog.profiling.mlt.io;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class MLTChunkTest {
  @Test
  public void equalsContract() {
    EqualsVerifier.forClass(MLTChunk.class)
        .withIgnoredFields("stringPool", "framePool", "stackPool", "writer")
        .verify();
  }
}
