import java.util.List;
import java.util.ArrayList;

public class CapturedSnapshot04 {

  public SimpleData createSimpleData() {
    SimpleData simpleData = new SimpleData("foo", 42);
    simpleData.listValue.add("bar1");
    simpleData.listValue.add("bar2");
    return simpleData;
  }

  public CompositeData createCompositeData() {
    CompositeData compositeData = new CompositeData(new SimpleData("foo1", 101), new SimpleData("foo2", 202));
    compositeData.l1.add(new SimpleData("bar1", 303));
    compositeData.l1.add(new SimpleData("bar2", 404));
    return compositeData;
  }

  public static int main(String arg) {
    CapturedSnapshot04 cs4 = new CapturedSnapshot04();
    SimpleData sdata = cs4.createSimpleData();
    CompositeData cdata = cs4.createCompositeData();
    SimpleData nullObject = null;
    return sdata.intValue + cdata.s1.intValue;
  }

  public static class SimpleData {
    private final String strValue;
    final int intValue;
    private final List<String> listValue = new ArrayList<>();

    public SimpleData(String strValue, int intValue) {
      this.strValue = strValue;
      this.intValue = intValue;
    }
  }

  public static class CompositeData {
    SimpleData s1;
    private SimpleData s2;
    SimpleData nullsd = null;
    private List<SimpleData> l1 = new ArrayList<>();

    public CompositeData(SimpleData s1, SimpleData s2) {
      this.s1 = s1;
      this.s2 = s2;
    }
  }
}
