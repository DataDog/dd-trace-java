package com.datadoghq.trace.impl;

import com.datadoghq.trace.Sampler;
import com.datadoghq.trace.impl.DDSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This sampler sample the traces at a predefined rate.
 * <p>
 * Keep (100 * `sample_rate`)% of the traces.
 * It samples randomly, its main purpose is to reduce the instrumentation footprint.
 */
public class RateSampler implements Sampler {

    /**
     * Maximum value for the traceId. FIXME: should be real uint64.
     */
    private static final double MAX_TRACE_ID_DOUBLE = (double)Long.MAX_VALUE;

    /**
     * Internal constant specific to Dadadog sampling technique.
     */
    private static final long SAMPLER_HASHER = 1111111111111111111L;

    private final static Logger logger = LoggerFactory.getLogger(RateSampler.class);
    /**
     * The sample rate used
     */
    private final double sampleRate;

    /**
     * Build an instance of the sampler. The Sample rate is fixed for each instance.
     *
     * @param sampleRate a number [0,1] representing the rate ratio.
     */
    public RateSampler(double sampleRate) {

        if (sampleRate <= 0) {
            sampleRate = 1;
            logger.error("SampleRate is negative or null, disabling the sampler");
        } else if (sampleRate > 1) {
            sampleRate = 1;
        }

        this.sampleRate = sampleRate;
        logger.debug("Initializing the RateSampler, sampleRate: {} %", this.sampleRate * 100);

    }

    @Override
    public boolean sample(DDSpan span) {
        long traceId = span.getTraceId();
        // Logical shift to move from signed to unsigned number. TODO: check that this is consistent with our Go implementation.
        boolean sample = ((span.getTraceId() * RateSampler.SAMPLER_HASHER) >>> 1) < this.sampleRate*RateSampler.MAX_TRACE_ID_DOUBLE;
        logger.debug("traceId: {} sampled: {}", traceId, sample);
        return sample;
    }

    public double getSampleRate() {
        return this.sampleRate;
    }

}
