package datadog.trace.bootstrap.jit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** {@code c.toArray(new T[c.size()])} and the helper must give equal arrays, of the same type. */
class CollectionFusionsTest {

  @Test
  void matchesThePresizedFormForJdkCollections() {
    for (int size : new int[] {0, 1, 3, 17}) {
      List<String> values = new ArrayList<>();
      for (int i = 0; i < size; i++) {
        values.add("v" + i);
      }
      Map<String, String> hashMap = new HashMap<>();
      Map<String, String> linkedMap = new LinkedHashMap<>();
      for (String v : values) {
        hashMap.put(v, v + "!");
        linkedMap.put(v, v + "!");
      }
      List<Collection<?>> collections =
          Arrays.asList(
              new ArrayList<>(values),
              new LinkedList<>(values),
              new ArrayDeque<>(values),
              new HashSet<>(values),
              new LinkedHashSet<>(values),
              new TreeSet<>(values),
              Arrays.asList(values.toArray()),
              hashMap.keySet(),
              hashMap.values(),
              linkedMap.keySet(),
              linkedMap.values(),
              new CopyOnWriteArrayList<>(values),
              Collections.unmodifiableList(values));
      for (Collection<?> c : collections) {
        Object[] expected = c.toArray(new Object[c.size()]);
        Object[] actual = CollectionFusions.toArrayPresized(c, new Object[0]);
        assertArrayEquals(expected, actual, c.getClass().getName());
        assertEquals(expected.getClass(), actual.getClass());
      }
      Collection<String> strings = new ArrayList<>(values);
      String[] expected = strings.toArray(new String[strings.size()]);
      Object[] actual = CollectionFusions.toArrayPresized(strings, new String[0]);
      assertArrayEquals(expected, actual);
      assertEquals(String[].class, actual.getClass());
    }
  }

  @Test
  void emptyCollectionsGetAFreshArray() {
    String[] zero = new String[0];
    Object[] first = CollectionFusions.toArrayPresized(new ArrayList<String>(), zero);
    Object[] second = CollectionFusions.toArrayPresized(new ArrayList<String>(), new String[0]);
    assertNotSame(first, second); // each call site allocates its own zero-length array
  }

  /** A collection whose size() is observable must see exactly the original's calls. */
  static final class CountingList extends ArrayList<String> {
    int sizeCalls;

    CountingList(List<String> values) {
      super(values);
    }

    @Override
    public int size() {
      sizeCalls++;
      return super.size();
    }
  }

  @Test
  void otherCollectionsRunTheOriginal() {
    CountingList original = new CountingList(Arrays.asList("a", "b"));
    String[] expected = original.toArray(new String[original.size()]);
    int originalCalls = original.sizeCalls;

    CountingList rewritten = new CountingList(Arrays.asList("a", "b"));
    Object[] actual = CollectionFusions.toArrayPresized(rewritten, new String[0]);
    assertArrayEquals(expected, actual);
    assertEquals(originalCalls, rewritten.sizeCalls);
  }

  @Test
  void nullCollectionThrowsLikeTheOriginal() {
    assertThrows(
        NullPointerException.class, () -> CollectionFusions.toArrayPresized(null, new Object[0]));
  }
}
