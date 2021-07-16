package datadog.trace.api.gateway;

/**
 * The {@code EventType} is a unique identifier for an event.
 *
 * @param <C> The type of the callback related to the {@code EventType}
 */
public class EventType<C> {
  private final String name;
  private final int id;

  protected EventType(final String name, final int id) {
    this.name = name;
    this.id = id;
  }

  public String getName() {
    return name;
  }

  int getId() {
    return id;
  }

  @Override
  public String toString() {
    return "EventType{" + "name='" + name + '\'' + '}';
  }
}
