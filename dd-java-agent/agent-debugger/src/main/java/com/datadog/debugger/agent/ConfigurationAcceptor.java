package com.datadog.debugger.agent;

import com.datadog.debugger.probe.ProbeDefinition;
import java.util.Collection;

public interface ConfigurationAcceptor {
  enum Source {
    REMOTE_CONFIG,
    SPAN_DEBUG,
    EXCEPTION
  }

  void accept(Source source, Collection<? extends ProbeDefinition> definitions);

  void handleException(String configId, Exception ex);
}
