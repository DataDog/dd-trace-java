package smoketest;

import java.util.List;

public class RequestBody {
  private List<KeyValue> main;
  private Object nullable;

  public List<KeyValue> getMain() {
    return main;
  }

  public void setMain(List<KeyValue> main) {
    this.main = main;
  }

  public Object getNullable() {
    return nullable;
  }

  public void setNullable(Object nullable) {
    this.nullable = nullable;
  }

  public static class KeyValue {
    private String key;
    private Double value;

    public String getKey() {
      return key;
    }

    public void setKey(String key) {
      this.key = key;
    }

    public Double getValue() {
      return value;
    }

    public void setValue(Double value) {
      this.value = value;
    }
  }
}
