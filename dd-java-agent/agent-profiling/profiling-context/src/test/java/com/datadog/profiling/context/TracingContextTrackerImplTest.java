package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.util.Base64Encoder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TracingContextTrackerImplTest {
  private TracingContextTrackerImpl instance;

  @BeforeEach
  void setup() throws Exception {
    instance = new TracingContextTrackerImpl(Allocators.heapAllocator(32, 16), 0L);
  }

  @Test
  void activateContext() {
    for (int i = 1; i <= 4; i++) {
      assertTrue(instance.activateContext(1L, i * 1000L));
    }
    assertFalse(instance.activateContext(1L, 10_000L));
  }

  @Test
  void deactivateContext() {}

  @Test
  void persist() {
    instance = new TracingContextTrackerImpl(Allocators.directAllocator(8192, 64), 0L);
    for (int i = 0; i < 40; i += 4) {
      instance.activateContext(1L, (i + 1) * 1_000_000L);
      instance.deactivateContext(1L, (i + 2) * 1_000_000L, false);
      instance.activateContext(2L, (i + 3) * 1_000_000L);
      instance.deactivateContext(2L, (i + 4) * 1_000_000L, true);
    }

    byte[] persisted = instance.persist();
    assertNotNull(persisted);

    List<IntervalParser.Interval> intervals = new IntervalParser().parseIntervals(persisted);
    assertEquals(11, intervals.size());

    byte[] encoded = new Base64Encoder(false).encode(persisted);
    System.out.println("===> encoded: " + encoded.length);
    System.err.println("===> " + new String(encoded, StandardCharsets.UTF_8));
  }

  @Test
  void testPersisted() {
    String[] encodedData = new String[]{
        "AAAAHvqGt8j+YAfDAQFGAlEB0wIB1AIB3AII7AIZAAAAuGY4J59CJdryAaj4Al9yASvwMSboAVICLzF6AW2IK5eeAZgUKBwsAXQwA97iARfuBDAeBhtsBk8uOgRSBgmkoKYJvgyaA1QEFAJUB2AGjfmeAUfs7ZAHpgHQm3YFtsMgAzDjoASkAfoE5Ac2AhQCIgGOG/QDegHIAcgHUAMkkMYCBg8UAcACJgIwDeoDBEwMBAQpBAE0xAbwAqICAAI6CzQCLASSA3ACHgJoBdYDfAa8AgoEFikkkkkkkkmiSSSWiSSSSSSSSSSSSSSSUSSSSSSSQAA",
        "AAAAJazpn6H8YAuBAgGPAgFBAUIDTAFPAVAJUg1oAfECBfQCGgAAATQgmYSCC2/+PTl08bAgo6ge7zJAyJwJmXogWWJ0Aa9EBFggsCYgq1FSAXu2IK8/DgG9gkqYcg8nfgF1DAT20BI1BhADPdgKVnduZ9oD8HIXIgNSgAaaAulCCWwG/zQBDa4OBf62ieIgpgpkA2RwA7dYAUhiBB/OBh/+CCIBOSgCdgEZ/APFA865TA+Oxh6mA2ga1AkeEcgFMnlkDjrBtAKwBswD6geUO3okoMiyEpnoBAFECpYcCEQSBa6s7AT4EyYCfAocJJIrHgEOspX2CAoCGpHIC+KmyAY4AmwFXvFsBAACCAR4BsIFbAHiA7woJgTWAs4CxpbWAjQCagK0BJakBHICKgPwRTwK+BrkA2BYHHNgCAwD5gI+AlgNQgMUBmoEsAJCAkQHGgSUBjgBwANOaRZSaRaaSSaJKKKKTLSSRRTKJJJJJJJbRRJJLRJJJJJJJJJJJJRJJJJJJJJJJJIAAA",
        "AAAAJNiIo7CTYQlGAlEB1QIB1gIB2QIB3AII3QIB4QEB7QIZAAAAwzf0nAKNmgK5qgH1jkXtzAEfOjcphOO6P8FaAWHGQz9sAfEyO1jKAXJEBJtQAS+iBlr4ByCUC0ebOAVRaAnimwQMwg94ArQEkgJIENBHmRQBHZRS/Dl+0guekMYBGgCUFggGAz6rnAXOlhgCiv18BvoCtAT8B74DzgHaAsQt8AREAuYBqAjsBFiQwAJ2FRwDTgHeAoQMKgSET/AC8ifEATEiBQACtAHGAoIPnAG8BIYC7gIOAloIpAbEB+gCSgR6SSSRSSSSSaJJJJSKaJJJJJJJJJJJJJJJJRJJJJJJJAAAAA"
    };
    for (String encoded : encodedData) {
      System.out.println("====");
      byte[] decoded = Base64.getDecoder().decode(encoded);

      for (IntervalParser.Interval i : new IntervalParser().parseIntervals(decoded)) {
        System.out.println("===> " + i.from + ":" + i.till + "  - " + (i.till - i.from));
      }
    }
  }

  @Test
  void varintTest() {
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    instance.putVarint(buffer, 298502924656L);
  }
}
