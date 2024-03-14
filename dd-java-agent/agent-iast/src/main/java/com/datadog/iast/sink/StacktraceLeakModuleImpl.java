package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.StacktraceLeakModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.annotation.Nonnull;

public class StacktraceLeakModuleImpl extends SinkModuleBase implements StacktraceLeakModule {

  public StacktraceLeakModuleImpl(@Nonnull Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onStacktraceLeak(
      Throwable throwable, String moduleName, String className, String methodName) {
    if (throwable != null) {
      final AgentSpan span = AgentTracer.activeSpan();
      Evidence evidence =
          new Evidence(
              "ExceptionHandler in "
                  + moduleName
                  + " \r\nthrown "
                  + throwable.getClass().getName());
      Location location = Location.forSpanAndClassAndMethod(span, className, methodName);
      report(span, new Vulnerability(VulnerabilityType.STACKTRACE_LEAK, location, evidence));
    }
  }
}
