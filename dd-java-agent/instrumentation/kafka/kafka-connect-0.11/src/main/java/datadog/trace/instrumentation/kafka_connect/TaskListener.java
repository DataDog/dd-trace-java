package datadog.trace.instrumentation.kafka_connect;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.apache.kafka.connect.runtime.TaskStatus.Listener;
import org.apache.kafka.connect.util.ConnectorTaskId;

public class TaskListener implements Listener {
  private final Listener delegate;

  public TaskListener(Listener delegate) {
    this.delegate = delegate;
  }

  @Override
  public void onStartup(ConnectorTaskId connectorTaskId) {
    AgentTracer.get().getDataStreamsMonitoring().setThreadServiceName(connectorTaskId.connector());
    delegate.onStartup(connectorTaskId);
  }

  @Override
  public void onPause(ConnectorTaskId connectorTaskId) {
    try {
      delegate.onPause(connectorTaskId);
    } finally {
      AgentTracer.get().getDataStreamsMonitoring().clearThreadServiceName();
    }
  }

  @Override
  public void onResume(ConnectorTaskId connectorTaskId) {
    AgentTracer.get().getDataStreamsMonitoring().setThreadServiceName(connectorTaskId.connector());
    delegate.onResume(connectorTaskId);
  }

  @Override
  public void onFailure(ConnectorTaskId connectorTaskId, Throwable throwable) {
    try {
      delegate.onFailure(connectorTaskId, throwable);
    } finally {
      AgentTracer.get().getDataStreamsMonitoring().clearThreadServiceName();
    }
  }

  @Override
  public void onShutdown(ConnectorTaskId connectorTaskId) {
    try {
      delegate.onShutdown(connectorTaskId);
    } finally {
      AgentTracer.get().getDataStreamsMonitoring().clearThreadServiceName();
    }
  }
}
