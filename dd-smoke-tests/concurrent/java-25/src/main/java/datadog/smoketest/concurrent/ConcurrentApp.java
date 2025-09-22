package datadog.smoketest.concurrent;

public class ConcurrentApp {
  void main(String[] args) {
    if (args.length != 1) {
      System.err.println("Missing test case argument");
      System.exit(1);
    }
    String name = args[0];
    try {
      fromName(name).run();
    } catch (InterruptedException e) {
      throw new RuntimeException("Failed to test case " + name, e);
    }
  }

  private static TestCase fromName(String name) {
    return switch (name) {
      case "NestedTasks" -> new NestedTasks();
      case "MultipleTasks" -> new MultipleTasks();
      case "SimpleCallableTask" -> new SimpleCallableTask();
      case "SimpleRunnableTask" -> new SimpleRunnableTask();
      default -> throw new IllegalArgumentException("Invalid test case name " + name);
    };
  }
}
