package datadog.trace.logging.intake;

import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import datadog.trace.api.logging.intake.LogsWriter;
import datadog.trace.util.AgentThreadFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogsWriterImpl implements LogsWriter {

  private static final Logger LOGGER = LoggerFactory.getLogger(LogsWriterImpl.class);

  private static final long POLLING_THREAD_SHUTDOWN_TIMEOUT_MILLIS = 5_000;
  private static final int ENQUEUE_LOG_TIMEOUT_MILLIS = 1_000;

  private final Map<String, Object> commonTags;
  private final BackendApiFactory apiFactory;
  private final Intake intake;
  private final BlockingQueue<Map<String, Object>> messageQueue;
  private final Thread messagePollingThread;

  public LogsWriterImpl(Config config, BackendApiFactory apiFactory, Intake intake) {
    this.apiFactory = apiFactory;
    this.intake = intake;

    commonTags = new HashMap<>();
    commonTags.put("ddsource", "java");
    commonTags.put("ddtags", "datadog.product:" + config.getAgentlessLogSubmissionProduct());
    commonTags.put("service", config.getServiceName());
    commonTags.put("hostname", config.getHostName());

    messageQueue = new ArrayBlockingQueue<>(config.getAgentlessLogSubmissionQueueSize());
    messagePollingThread =
        AgentThreadFactory.newAgentThread(
            AgentThreadFactory.AgentThread.LOGS_INTAKE, this::logPollingLoop);
  }

  @Override
  public void start() {
    try {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(AGENT_THREAD_GROUP, this::shutdown, "dd-logs-intake-shutdown-hook"));
      messagePollingThread.start();
    } catch (final IllegalStateException ex) {
      // The JVM is already shutting down.
    }
  }

  @Override
  public void shutdown() {
    if (!messagePollingThread.isAlive()) {
      return;
    }

    messagePollingThread.interrupt();
    try {
      messagePollingThread.join(POLLING_THREAD_SHUTDOWN_TIMEOUT_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.debug("Interrupted while waiting for log polling thread to stop");
    }
  }

  @Override
  public void log(Map<String, Object> message) {
    try {
      message.putAll(commonTags);

      if (!messageQueue.offer(message, ENQUEUE_LOG_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        LOGGER.debug("Timeout while trying to enqueue log message");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.debug("Interrupted while trying to log");
    }
  }

  private void logPollingLoop() {
    BackendApi backendApi = apiFactory.createBackendApi(intake);
    LogsDispatcher logsDispatcher = new LogsDispatcher(backendApi);

    while (!Thread.currentThread().isInterrupted()) {
      try {
        List<Map<String, Object>> batch = new ArrayList<>();
        batch.add(messageQueue.take());
        messageQueue.drainTo(batch);
        logsDispatcher.dispatch(batch);

      } catch (InterruptedException e) {
        break;
      }
    }

    List<Map<String, Object>> batch = new ArrayList<>();
    messageQueue.drainTo(batch);
    if (!batch.isEmpty()) {
      logsDispatcher.dispatch(batch);
    }
  }
}
