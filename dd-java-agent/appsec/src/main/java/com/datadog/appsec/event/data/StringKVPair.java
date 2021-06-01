package com.datadog.appsec.event.data;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;

public class StringKVPair implements List<String> {
  private final String key;
  private final String value;

  public StringKVPair(String key, String value) {
    if (key == null) {
      key = "";
    }
    if (value == null) {
      value = "";
    }
    this.key = key;
    this.value = value;
  }

  public String getKey() {
    return key;
  }

  public String getValue() {
    return value;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    StringKVPair otherPair = (StringKVPair) o;
    return Objects.equals(key, otherPair.key) && Objects.equals(value, otherPair.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key, value);
  }

  // List implementation follows

  @Override
  public int size() {
    return 2;
  }

  @Override
  public boolean isEmpty() {
    return false;
  }

  @Override
  public boolean contains(Object o) {
    return key.equals(o) || value.equals(o);
  }

  @Override
  public Iterator<String> iterator() {
    return listIterator(0);
  }

  @Override
  public String[] toArray() {
    return new String[] {key, value};
  }

  @Override
  public <T> T[] toArray(T[] a) {
    if (a.length < 2) {
      return (T[]) toArray();
    }
    a[0] = (T) key;
    a[1] = (T) value;
    return a;
  }

  @Override
  public boolean add(String s) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean remove(Object o) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    for (Object o : c) {
      if (!contains(o)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean addAll(Collection<? extends String> c) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean addAll(int index, Collection<? extends String> c) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clear() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String get(int index) {
    if (index == 0) {
      return key;
    } else if (index == 1) {
      return value;
    } else {
      throw new ArrayIndexOutOfBoundsException();
    }
  }

  @Override
  public String set(int index, String element) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void add(int index, String element) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String remove(int index) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int indexOf(Object o) {
    if (key.equals(o)) {
      return 0;
    } else if (value.equals(o)) {
      return 1;
    } else {
      return -1;
    }
  }

  @Override
  public int lastIndexOf(Object o) {
    if (value.equals(o)) {
      return 1;
    } else if (key.equals(o)) {
      return 0;
    } else {
      return -1;
    }
  }

  @Override
  public ListIterator<String> listIterator() {
    return listIterator(0);
  }

  @Override
  public ListIterator<String> listIterator(final int index) {
    if (index < 0 || index > 2) {
      throw new IndexOutOfBoundsException();
    }
    return new ListIterator<String>() {
      private int nextPos = index;

      @Override
      public boolean hasNext() {
        return nextPos < 2;
      }

      @Override
      public String next() {
        if (nextPos == 0) {
          nextPos++;
          return key;
        } else if (nextPos == 1) {
          nextPos++;
          return value;
        } else {
          throw new NoSuchElementException();
        }
      }

      @Override
      public boolean hasPrevious() {
        return nextPos > 0;
      }

      @Override
      public String previous() {
        if (nextPos == 2) {
          nextPos--;
          return key;
        } else {
          throw new NoSuchElementException();
        }
      }

      @Override
      public int nextIndex() {
        return nextPos;
      }

      @Override
      public int previousIndex() {
        return nextPos - 1;
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException();
      }

      @Override
      public void set(String s) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void add(String s) {
        throw new UnsupportedOperationException();
      }
    };
  }

  @Override
  public List<String> subList(int fromIndex, int toIndex) {
    if (fromIndex < 0 || toIndex > 2 || fromIndex > toIndex) {
      throw new IndexOutOfBoundsException();
    }
    List<String> strings = new LinkedList<>();
    for (int i = fromIndex; i < toIndex; i++) {
      strings.add(get(i));
    }
    return strings;
  }
}
