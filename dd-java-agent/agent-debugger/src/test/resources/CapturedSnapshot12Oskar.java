import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CapturedSnapshot12Oskar {
  public static int main(int arg) {
    List<Integer> list = Arrays.asList(1, 2, 3);
    int sum = list
        .stream()
        .mapToInt(x -> x + 1).map(x -> x - 12)
        .sum();
    return sum;
  }

  public static int foo(int arg) {
    return Arrays.asList(1, 2, 3, 4)
        .stream()
        .mapToInt(x -> x + 1).map(x -> x - 12)
        .sum();
  }
}
