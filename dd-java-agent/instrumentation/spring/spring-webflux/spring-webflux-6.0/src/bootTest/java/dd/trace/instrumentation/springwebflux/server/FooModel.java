package dd.trace.instrumentation.springwebflux.server;

public class FooModel {
  public long id;
  public String name;

  public FooModel(long id, String name) {
    this.id = id;
    this.name = name;
  }

  public long getId() { return id; }
  public String getName() { return name; }

  @Override
  public String toString() {
    return "{\"id\":" + id + ",\"name\":\"" + name + "\"}";
  }
}
