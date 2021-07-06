
package datadog.trace;

import datadog.cws.tls.CwsTls;
import datadog.trace.api.DDId;

class TestCwsTls implements CwsTls {
    private DDId lastTraceId;
    private DDId lastSpanId;

    public void registerSpan(DDId traceId, DDId spanId) {
        lastTraceId = traceId;
        lastSpanId = spanId;
    }

    public DDId getSpanId() {
        return lastSpanId;
    }

    public DDId getTraceId() {
        return lastTraceId;
    }
}