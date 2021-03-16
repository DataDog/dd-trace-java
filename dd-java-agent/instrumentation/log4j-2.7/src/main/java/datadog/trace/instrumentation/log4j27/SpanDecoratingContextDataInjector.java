/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package datadog.trace.instrumentation.log4j27;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
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

    // We're at most adding 5 tags
    StringMap newContextData = new SortedArrayStringMap(contextData.size() + 5);

    if (Config.get().isLogsMDCTagsInjectionEnabled()) {
      newContextData.putValue(Tags.DD_ENV, Config.get().getEnv());
      newContextData.putValue(Tags.DD_SERVICE, Config.get().getServiceName());
      newContextData.putValue(Tags.DD_VERSION, Config.get().getVersion());
    }

    AgentSpan span = activeSpan();

    if (span != null) {
      newContextData.putValue(
          CorrelationIdentifier.getSpanIdKey(), span.context().getSpanId().toString());
      newContextData.putValue(
          CorrelationIdentifier.getTraceIdKey(), span.context().getTraceId().toString());
    }

    newContextData.putAll(contextData);
    return newContextData;
  }

  @Override
  public ReadOnlyStringMap rawContextData() {
    return delegate.rawContextData();
  }
}
