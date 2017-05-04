package com.datadoghq.trace.impl;

import com.datadoghq.trace.Sampler;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;



public class RateSamplerTest {

    @Test
    public void testRateSampler() {

        DDSpan mockSpan = mock(DDSpan.class);

        when(mockSpan.getTraceId()).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock s) throws Throwable {
                return ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
            }
        });

        final double sampleRate = 0.35;
        final int iterations = 1000;
        Sampler sampler = new RateSampler(sampleRate);

        int kept = 0;

        for (int i = 0; i < iterations; i++) {
            if (sampler.sample(mockSpan)) {
                kept++;
            }
        }
        //TODO Make it deterministic with a seeded random? So far, it is works good enough.
        assertThat(((double) kept / iterations)).isBetween(sampleRate - 0.02, sampleRate + 0.02);

    }

    @Test
    public void testRateBoundaries() {

        RateSampler sampler = new RateSampler(1000);
        assertThat(sampler.getSampleRate()).isEqualTo(1);

        sampler = new RateSampler(-1000);
        assertThat(sampler.getSampleRate()).isEqualTo(1);

        sampler = new RateSampler(0.337);
        assertThat(sampler.getSampleRate()).isEqualTo(0.337);

    }
}
