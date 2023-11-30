package datadog.trace.api;

public interface Stateful extends AutoCloseable {

  Stateful DEFAULT =
      new Stateful() {
        @Override
        public void close() {}

        @Override
        public void activate(Object context) {}
      };

  @Override
  void close();

  void activate(Object context);
}
