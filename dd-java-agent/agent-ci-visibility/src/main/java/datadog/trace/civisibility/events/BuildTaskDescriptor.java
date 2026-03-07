package datadog.trace.civisibility.events;

import datadog.trace.util.HashingUtils;

public class BuildTaskDescriptor<T> {

  private final T sessionKey;
  private final String taskName;

  public BuildTaskDescriptor(T sessionKey, String taskName) {
    this.sessionKey = sessionKey;
    this.taskName = taskName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    BuildTaskDescriptor<?> that = (BuildTaskDescriptor<?>) o;
    return sessionKey.equals(that.sessionKey) && taskName.equals(that.taskName);
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(sessionKey, taskName);
  }
}
