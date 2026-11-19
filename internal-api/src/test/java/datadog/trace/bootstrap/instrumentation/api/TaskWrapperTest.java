package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TaskWrapperTest {

  @Test
  void testUnwrapping() {
    // when: direct wrapping
    DirectWrapper directWrapper = new DirectWrapper();
    assertEquals(Integer.class, TaskWrapper.getUnwrappedType(directWrapper));

    // when: wrapped value is null
    RecursiveWrapper nullWrapper = new RecursiveWrapper();
    assertNull(TaskWrapper.getUnwrappedType(nullWrapper));

    // when: recursive wrapped value within depth limit
    RecursiveWrapper shallowRecursiveWrapper = new RecursiveWrapper();
    shallowRecursiveWrapper.wrapped = new DirectWrapper();
    assertEquals(Integer.class, TaskWrapper.getUnwrappedType(shallowRecursiveWrapper));

    // when: recursive wrapped value exceeds depth limit
    DirectWrapper level1 = new DirectWrapper();
    RecursiveWrapper level2 = new RecursiveWrapper();
    level2.wrapped = level1;
    RecursiveWrapper level3 = new RecursiveWrapper();
    level3.wrapped = level2;
    RecursiveWrapper level4 = new RecursiveWrapper();
    level4.wrapped = level3;
    RecursiveWrapper level5 = new RecursiveWrapper();
    level5.wrapped = level4;
    RecursiveWrapper level6 = new RecursiveWrapper();
    level6.wrapped = level5;
    RecursiveWrapper deepRecursiveWrapper = level6;
    // then: terminate at max depth
    assertEquals(DirectWrapper.class, TaskWrapper.getUnwrappedType(deepRecursiveWrapper));

    // when: cycle
    RecursiveWrapper outer = new RecursiveWrapper();
    RecursiveWrapper inner = new RecursiveWrapper();
    outer.wrapped = inner;
    inner.wrapped = outer;
    // then: terminate at max depth
    assertEquals(RecursiveWrapper.class, TaskWrapper.getUnwrappedType(outer));
    assertEquals(RecursiveWrapper.class, TaskWrapper.getUnwrappedType(inner));
  }

  static class RecursiveWrapper implements TaskWrapper {

    TaskWrapper wrapped;

    @Override
    public Object $$DD$$__unwrap() {
      return wrapped;
    }
  }

  static class DirectWrapper implements TaskWrapper {

    Integer field = 1;

    @Override
    public Object $$DD$$__unwrap() {
      return field;
    }
  }
}
