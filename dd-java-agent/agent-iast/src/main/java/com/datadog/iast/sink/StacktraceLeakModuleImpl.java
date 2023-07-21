package com.datadog.iast.sink;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.Config;
import datadog.trace.api.iast.sink.StacktraceLeakModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

import javax.annotation.Nullable;

public class StacktraceLeakModuleImpl extends SinkModuleBase implements StacktraceLeakModule {
    @Override
    public void onResponseException(@Nullable String exception) {
        final AgentSpan span = AgentTracer.activeSpan();
        String serviceName = Config.get().getServiceName();
        reporter.report(
                span,
                new Vulnerability(
                        VulnerabilityType.STACKTRACE_LEAK,
                        null,
                        new Evidence(serviceName)));
    }
}
