import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public class JavaPropagation implements Supplier<List<String>> {

  @Override
  public List<String> get() {
    return Arrays.asList("plus", "concat");
  }

  public String plus(String tainted) {
    return tainted + tainted;
  }

  public String concat(String tainted) {
    return tainted.concat(tainted);
  }
}
