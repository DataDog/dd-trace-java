package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LLMObsSamplerTest {

  @Test
  void keepsEverythingAtRateOne() {
    LLMObsSampler sampler = new LLMObsSampler(1.0);
    assertEquals("1", sampler.formattedRate());
    for (long id : new long[] {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 1234567890123L}) {
      assertTrue(sampler.sample(id));
    }
  }

  @Test
  void dropsEverythingAtRateZero() {
    LLMObsSampler sampler = new LLMObsSampler(0.0);
    assertEquals("0", sampler.formattedRate());
    // 0 is the one sampling id whose Knuth product is the minimum value, so it sits exactly on the
    // cutoff at rate 0. Every other id must be dropped.
    for (long id : new long[] {1L, -1L, Long.MAX_VALUE, 1234567890123L}) {
      assertFalse(sampler.sample(id));
    }
  }

  @ParameterizedTest
  @ValueSource(doubles = {-1.0, -0.0001, 1.0001, 2.0, Double.NaN})
  void clampsOutOfRangeRates(double rate) {
    LLMObsSampler sampler = new LLMObsSampler(rate);
    // NaN fails every comparison, so it clamps to the same "keep everything" behaviour as 1.0.
    if (rate < 0.0) {
      assertEquals("0", sampler.formattedRate());
    } else {
      assertEquals("1", sampler.formattedRate());
      assertTrue(sampler.sample(1234567890123L));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "1.0, 1",
    "0.0, 0",
    "0.5, 0.5",
    "0.25, 0.25",
    "0.1, 0.1",
    "0.123456, 0.123456",
    // Beyond 6 digits of precision the rate rounds, matching the _dd.p.ksr format exactly.
    "0.1234567, 0.123457",
    "0.0000001, 0",
    "0.999999, 0.999999",
  })
  void formatsRateLikeKnuthSamplingRateTag(double rate, String expected) {
    assertEquals(expected, LLMObsSampler.formatRate(rate));
  }

  @Test
  void isDeterministicForTheSameSamplingId() {
    LLMObsSampler sampler = new LLMObsSampler(0.5);
    long id = 987654321987654321L;
    boolean first = sampler.sample(id);
    for (int i = 0; i < 100; i++) {
      assertEquals(first, sampler.sample(id));
    }
    // Two samplers configured with the same rate must agree, which is what lets independently
    // configured services reach the same decision for a distributed trace.
    assertEquals(first, new LLMObsSampler(0.5).sample(id));
  }

  @ParameterizedTest
  @ValueSource(doubles = {0.1, 0.25, 0.5, 0.75, 0.9})
  void keepsRoughlyTheConfiguredFraction(double rate) {
    LLMObsSampler sampler = new LLMObsSampler(rate);
    int iterations = 100_000;
    int kept = 0;
    for (int i = 0; i < iterations; i++) {
      if (sampler.sample(ThreadLocalRandom.current().nextLong())) {
        kept++;
      }
    }
    double observed = (double) kept / iterations;
    assertTrue(
        Math.abs(observed - rate) < 0.02,
        "expected ~" + rate + " of traces kept but observed " + observed);
  }

  @Test
  void higherRatesAreASupersetOfLowerRates() {
    // A trace kept at 10% must also be kept at 50%: the cutoff only moves outward as the rate
    // rises. This is what makes a service configured at a higher rate safe to add to a trace.
    LLMObsSampler low = new LLMObsSampler(0.1);
    LLMObsSampler high = new LLMObsSampler(0.5);
    for (int i = 0; i < 10_000; i++) {
      long id = ThreadLocalRandom.current().nextLong();
      if (low.sample(id)) {
        assertTrue(high.sample(id), "id " + id + " kept at 0.1 but dropped at 0.5");
      }
    }
  }
}
