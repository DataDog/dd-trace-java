package datadog.smoketest.concurrent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveTask;

// examples from https://www.baeldung.com/java-fork-join
public class demoForkJoin {
  public static void main(String[] args) {
    System.out.println("=====demoForkJoin start=====");

    // instantiate forkJoinPool
    ForkJoinPool forkJoinPool = ForkJoinPool.commonPool();

    // execute forkJoinPool tasks
    Random random = new Random();
    int[] arr = new int[20];
    for (int i = 0; i < arr.length; i++) {
      arr[i] = random.nextInt(20);
    }

    CustomRecursiveTask customRecursiveTask = new CustomRecursiveTask(arr);
    forkJoinPool.execute(customRecursiveTask);
    customRecursiveTask.join();

    CustomRecursiveTask customRecursiveTask1 = new CustomRecursiveTask(arr);
    CustomRecursiveTask customRecursiveTask2 = new CustomRecursiveTask(arr);
    CustomRecursiveTask customRecursiveTask3 = new CustomRecursiveTask(arr);
    customRecursiveTask1.fork();
    customRecursiveTask2.fork();
    customRecursiveTask3.fork();

    // join the results
    int result = 0;
    result += customRecursiveTask3.join();
    result += customRecursiveTask2.join();
    result += customRecursiveTask1.join();

    // print the result
    System.out.println("ForkJoinPool result: " + result);

    System.out.println("=====demoForkJoin finish=====");
  }

  public static class CustomRecursiveTask extends RecursiveTask<Integer> {
    private int[] arr;

    private static final int THRESHOLD = 20;

    public CustomRecursiveTask(int[] arr) {
      this.arr = arr;
    }

    @Override
    protected Integer compute() {
      if (arr.length > THRESHOLD) {
        return ForkJoinTask.invokeAll(createSubtasks()).stream().mapToInt(ForkJoinTask::join).sum();
      } else {
        return processing(arr);
      }
    }

    private Collection<CustomRecursiveTask> createSubtasks() {
      List<CustomRecursiveTask> dividedTasks = new ArrayList<>();
      dividedTasks.add(new CustomRecursiveTask(Arrays.copyOfRange(arr, 0, arr.length / 2)));
      dividedTasks.add(
          new CustomRecursiveTask(Arrays.copyOfRange(arr, arr.length / 2, arr.length)));
      return dividedTasks;
    }

    private Integer processing(int[] arr) {
      return Arrays.stream(arr).filter(a -> a > 10 && a < 27).map(a -> a * 10).sum();
    }
  }
}
