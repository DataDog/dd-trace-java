package datadog.trace.instrumentation.kafka_connect;

import org.apache.kafka.connect.runtime.TaskStatus.Listener;
import org.apache.kafka.connect.util.ConnectorTaskId;

public class TaskListener implements Listener{
  final private Listener delegate;
  public TaskListener(Listener delegate) {
    this.delegate = delegate;

  }
  @Override
  public void onStartup(ConnectorTaskId connectorTaskId) {
    System.out.println("start up" + connectorTaskId.connector());
    delegate.onStartup(connectorTaskId);
  }

  @Override
  public void onPause(ConnectorTaskId connectorTaskId) {
    System.out.println("pause" + connectorTaskId.connector());
    delegate.onPause(connectorTaskId);
  }

  @Override
  public void onResume(ConnectorTaskId connectorTaskId) {
    System.out.println("resume" + connectorTaskId.connector());
    delegate.onResume(connectorTaskId);
  }

  @Override
  public void onFailure(ConnectorTaskId connectorTaskId, Throwable throwable) {
    System.out.println("failure" + connectorTaskId.connector());
    delegate.onFailure(connectorTaskId, throwable);
  }

  @Override
  public void onShutdown(ConnectorTaskId connectorTaskId) {
    System.out.println("shutdown" + connectorTaskId.connector());
    delegate.onShutdown(connectorTaskId);
  }
}
