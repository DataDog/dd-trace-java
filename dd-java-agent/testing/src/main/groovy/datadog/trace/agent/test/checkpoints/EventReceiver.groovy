package datadog.trace.agent.test.checkpoints

interface EventReceiver<T> {
  T onEvent(Event event)
}
