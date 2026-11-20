package com.datadog.openfeature.internal.ufc;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.RandomAccess;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class CompactListTest {

  @TableTest({
    "size",
    "0   ",
    "1   ",
    "2   ",
    "5   "
  })
  void holdsTheElementsInOrder(final int size) {
    final List<String> source = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      source.add("e" + i);
    }

    final List<String> list = CompactList.copyOf(source);

    assertEquals(size, list.size());
    assertEquals(source, list);
    assertEquals(source.hashCode(), list.hashCode());
    final Iterator<String> iterator = list.iterator();
    for (int i = 0; i < size; i++) {
      assertEquals("e" + i, list.get(i));
      assertEquals("e" + i, iterator.next());
    }
    assertFalse(iterator.hasNext());
    assertThrows(NoSuchElementException.class, iterator::next);
    assertTrue(list instanceof RandomAccess);
    assertEquals(size == 0, list.isEmpty());

    final List<String> visited = new ArrayList<>();
    list.forEach(visited::add);
    assertEquals(source, visited);
  }

  @Test
  void returnsANewIteratorEveryTimeEvenWhenEmpty() {
    // A shared iterator on any path would be a merged allocation, which escape analysis can't
    // remove.
    final List<String> empty = CompactList.copyOf(Collections.emptyList());
    assertNotSame(empty.iterator(), empty.iterator());
    final List<String> one = CompactList.copyOf(Collections.singletonList("a"));
    assertNotSame(one.iterator(), one.iterator());
  }

  @Test
  void sharesTheEmptyList() {
    assertSame(CompactList.copyOf(new ArrayList<>()), CompactList.copyOf(Collections.emptyList()));
  }

  @Test
  void keepsNullElements() {
    assertEquals(
        Collections.singletonList(null), CompactList.copyOf(Collections.singletonList(null)));
    final List<String> list = CompactList.copyOf(asList("a", null, "b"));
    assertNull(list.get(1));
    assertTrue(list.contains(null));
    assertEquals(1, list.indexOf(null));
  }

  @Test
  void rejectsOutOfBoundsIndexes() {
    final List<String> list = CompactList.copyOf(asList("a", "b"));
    assertThrows(IndexOutOfBoundsException.class, () -> list.get(2));
    assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
    assertThrows(
        IndexOutOfBoundsException.class, () -> CompactList.copyOf(new ArrayList<>()).get(0));
  }

  @Test
  void isImmutable() {
    final List<String> list = CompactList.copyOf(asList("a", "b"));
    assertThrows(UnsupportedOperationException.class, () -> list.add("c"));
    assertThrows(UnsupportedOperationException.class, () -> list.set(0, "c"));
    assertThrows(UnsupportedOperationException.class, () -> list.remove(0));
  }

  @Test
  void doesNotShareTheSourceArray() {
    final List<String> source = new ArrayList<>(asList("a", "b", "c"));
    final List<String> list = CompactList.copyOf(source);
    source.set(2, "z");
    assertEquals(asList("a", "b", "c"), list);
  }
}
