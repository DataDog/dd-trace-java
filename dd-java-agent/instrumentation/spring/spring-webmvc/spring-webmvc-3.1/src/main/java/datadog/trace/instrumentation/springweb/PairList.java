package datadog.trace.instrumentation.springweb;

import java.util.AbstractList;

public class PairList extends AbstractList<Object> {
  private final Object obj1;
  private final Object obj2;

  public PairList(Object obj1, Object obj2) {
    this.obj1 = obj1;
    this.obj2 = obj2;
  }

  @Override
  public Object get(int index) {
    if (index == 0) {
      return obj1;
    } else if (index == 1) {
      return obj2;
    } else {
      throw new IndexOutOfBoundsException("Valid indices are 0 and 1");
    }
  }

  @Override
  public int size() {
    return 2;
  }
}
