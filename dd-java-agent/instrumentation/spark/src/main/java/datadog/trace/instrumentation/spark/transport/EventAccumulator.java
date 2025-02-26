package datadog.trace.instrumentation.spark.transport;

import java.util.ArrayList;
import java.util.List;

public final class EventAccumulator {

  private static EventAccumulator INSTANCE;
  private List<Object> runEvents = new ArrayList<>();
  private List<Object> datasetEvents = new ArrayList<>();
  private List<Object> jobEvents = new ArrayList<>();

  private EventAccumulator() {}

  public static EventAccumulator getInstance() {
    if (INSTANCE == null) {
      INSTANCE = new EventAccumulator();
    }
    return INSTANCE;
  }

  public static void addRunEvent(Object event) {
    getInstance().runEvents.add(event);
  }

  public static void addDatasetEvent(Object event) {
    getInstance().datasetEvents.add(event);
  }

  public static void addJobEvent(Object event) {
    getInstance().jobEvents.add(event);
  }

  public List<Object> getRunEvents() {
    return runEvents;
  }

  public List<Object> getDatasetEvents() {
    return datasetEvents;
  }

  public List<Object> getJobEvents() {
    return jobEvents;
  }
}
