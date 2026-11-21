package datadog.trace.util;

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
import java.util.LinkedHashSet;
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

    final CompactList<String> list = CompactList.copyOf(source);

    assertEquals(size, list.size());
    assertEquals(size == 0, list.isEmpty());
    assertEquals(source, list);
    assertEquals(source.hashCode(), list.hashCode());
    assertTrue(list instanceof RandomAccess);

    final Iterator<String> iterator = list.iterator();
    final Cursor<String> cursor = list.cursor();
    for (int i = 0; i < size; i++) {
      assertEquals("e" + i, list.get(i));
      assertTrue(iterator.hasNext());
      assertEquals("e" + i, iterator.next());
      assertEquals("e" + i, cursor.next());
    }
    assertFalse(iterator.hasNext());
    assertThrows(NoSuchElementException.class, iterator::next);
    assertNull(cursor.next());
    assertNull(cursor.next());

    final List<String> visited = new ArrayList<>();
    list.forEach(visited::add);
    assertEquals(source, visited);
  }

  @Test
  void returnsANewIteratorAndCursorEveryTimeEvenWhenEmpty() {
    // A shared walker on any path would be a merged allocation, which escape analysis can't
    // remove.
    final CompactList<String> empty = CompactList.empty();
    assertNotSame(empty.iterator(), empty.iterator());
    assertNotSame(empty.cursor(), empty.cursor());
    final CompactList<String> one = CompactList.copyOf(Collections.singletonList("a"));
    assertNotSame(one.iterator(), one.iterator());
    assertNotSame(one.cursor(), one.cursor());
  }

  @Test
  void sharesTheEmptyList() {
    assertSame(CompactList.empty(), CompactList.copyOf(new ArrayList<>()));
    assertSame(CompactList.empty(), CompactList.copyOf(Collections.emptySet()));
  }

  @Test
  void copiesAnyCollectionInIterationOrder() {
    final LinkedHashSet<String> set = new LinkedHashSet<>(asList("c", "a", "b"));
    assertEquals(asList("c", "a", "b"), CompactList.copyOf(set));
  }

  @Test
  void doesNotCopyACompactList() {
    final CompactList<String> list = CompactList.copyOf(asList("a", "b"));
    assertSame(list, CompactList.copyOf(list));
  }

  @Test
  void keepsNullElements() {
    assertEquals(
        Collections.singletonList(null), CompactList.copyOf(Collections.singletonList(null)));
    final CompactList<String> list = CompactList.copyOf(asList("a", null, "b"));
    assertNull(list.get(1));
    assertTrue(list.contains(null));
    assertEquals(1, list.indexOf(null));
  }

  @Test
  void rejectsOutOfBoundsIndexes() {
    final CompactList<String> list = CompactList.copyOf(asList("a", "b"));
    assertThrows(IndexOutOfBoundsException.class, () -> list.get(2));
    assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
    assertThrows(IndexOutOfBoundsException.class, () -> CompactList.empty().get(0));
  }

  @Test
  void isImmutable() {
    final CompactList<String> list = CompactList.copyOf(asList("a", "b"));
    assertThrows(UnsupportedOperationException.class, () -> list.add("c"));
    assertThrows(UnsupportedOperationException.class, () -> list.set(0, "c"));
    assertThrows(UnsupportedOperationException.class, () -> list.remove(0));
  }

  @Test
  void doesNotShareTheSourceArray() {
    final List<String> source = new ArrayList<>(asList("a", "b", "c"));
    final CompactList<String> list = CompactList.copyOf(source);
    source.set(2, "z");
    assertEquals(asList("a", "b", "c"), list);
  }
}
