import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Random;

public class CapturedSnapshot06 {

  private static final String STR_CONSTANT = "strConst";
  private static final int INT_CONSTANT = 1001;
  private static String STATIC_STR = "strStatic";

  private int intValue = 24;
  private final double doubleValue = 3.14;
  private String strValue = "foobar";
  private final List<String> strList = new ArrayList<>(Arrays.asList("foo", "bar"));
  private final Map<String, String> strMap = new HashMap<>();
  private final String[] strArray = new String[] {"foo", "bar"};
  private final long[] longArray = new long[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
  private final boolean[] boolArray = new boolean[] {true, false, true, false, true, false, true};
  private final Set<String> strSet = new HashSet<>(Arrays.asList("foo", "bar"));
  private final Random random = new Random();
  private StandardOpenOption option =  StandardOpenOption.READ;
  private Object objectStrValue = strValue;

  int f() {
    intValue *= 2;
    strValue = "done";
    strList.add(strValue);
    strMap.put("foo", "bar");
    return 42; // beae1817-f3b0-4ea8-a74f-000000000001
  }

  public static int main(String arg) {
    if ("f".equals(arg)) {
      CapturedSnapshot06 cs6 = new CapturedSnapshot06();
      return cs6.f();
    } else {
      Base base = new Inherited();
      return base.f();
    }
  }


  static class Base {
    private int intValue = 24;
    protected double doubleValue = 3.14;
    private Object obj1;

    public Base(Object obj1) {
      this.obj1 = obj1;
    }

    public int f() {
      intValue *= 2;
      return 42;
    }
  }

  static class Inherited extends Base {
    private static final Object OBJ = new Object();
    private String strValue = "foobar";
    private Object obj2;

    public Inherited() {
      super(new Base(OBJ));
      this.obj2 = new Base(new Object());
    }

    public int f() {
      strValue = "barfoo";
      doubleValue *= 2;
      return super.f();
    }
  }
}
