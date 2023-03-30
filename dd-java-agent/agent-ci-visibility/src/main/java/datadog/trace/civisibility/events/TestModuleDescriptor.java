package datadog.trace.civisibility.events;

import java.util.Objects;

public class TestModuleDescriptor<T> {

  private final T sessionKey;
  private final String moduleName;

  public TestModuleDescriptor(T sessionKey, String moduleName) {
    this.sessionKey = sessionKey;
    this.moduleName = moduleName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TestModuleDescriptor<?> that = (TestModuleDescriptor<?>) o;
    return sessionKey.equals(that.sessionKey) && moduleName.equals(that.moduleName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sessionKey, moduleName);
  }
}
