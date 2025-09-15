/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package datadog.trace.instrumentation.log4j27;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;

import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.List;
import org.apache.logging.log4j.core.ContextDataInjector;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.util.ReadOnlyStringMap;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.apache.logging.log4j.util.StringMap;

public final class SpanDecoratingContextDataInjector implements ContextDataInjector {
  private final ContextDataInjector delegate;

  public SpanDecoratingContextDataInjector(ContextDataInjector delegate) {
    this.delegate = delegate;
  }

  @Override
  public StringMap injectContextData(List<Property> list, StringMap reusable) {
    StringMap contextData = delegate.injectContextData(list, reusable);
    AgentSpan span = activeSpan();

    if (!traceConfig(span).isLogsInjectionEnabled()) {
      return contextData;
    }

    // We're at most adding 5 tags
    StringMap newContextData = new SortedArrayStringMap(contextData.size() + 5);

    String env = Config.get().getEnv();
    if (null != env && !env.isEmpty()) {
      newContextData.putValue(Tags.DD_ENV, env);
    }
    String serviceName = Config.get().getServiceName();
    if (null != serviceName && !serviceName.isEmpty()) {
      newContextData.putValue(Tags.DD_SERVICE, serviceName);
    }
    String version = Config.get().getVersion();
    if (null != version && !version.isEmpty()) {
      newContextData.putValue(Tags.DD_VERSION, version);
    }
    if (span != null) {
      DDTraceId traceId = span.context().getTraceId();
      String traceIdValue =
          Config.get().isLogs128bitTraceIdEnabled() && traceId.toHighOrderLong() != 0
              ? traceId.toHexString()
              : traceId.toString();
      newContextData.putValue(CorrelationIdentifier.getTraceIdKey(), traceIdValue);
      newContextData.putValue(
          CorrelationIdentifier.getSpanIdKey(), DDSpanId.toString(span.context().getSpanId()));
    }

    newContextData.putAll(contextData);
    return newContextData;
  }

  @Override
  public ReadOnlyStringMap rawContextData() {
    return delegate.rawContextData();
  }
}
