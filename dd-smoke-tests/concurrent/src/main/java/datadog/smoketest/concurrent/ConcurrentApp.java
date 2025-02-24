package datadog.smoketest.concurrent;

public class ConcurrentApp {
  public static void main(String[] args) {
    System.out.println("=====ConcurrentApp start=====");

    // demo ExecutorService
    demoExecutorService.main(args);

    // demo ForkJoin
    demoForkJoin.main(args);

    // demo custom spans

    // demo something else?

    System.out.println("=====ConcurrentApp finish=====");
  }
}
