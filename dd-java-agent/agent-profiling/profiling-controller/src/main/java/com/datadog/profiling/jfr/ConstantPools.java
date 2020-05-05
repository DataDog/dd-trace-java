package com.datadog.profiling.jfr;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A per-type map of {@linkplain ConstantPool} instances */
final class ConstantPools implements Iterable<ConstantPool> {
  private final Map<JFRType, ConstantPool> constantPoolMap = new HashMap<>();

  /**
   * Get the {@linkplain ConstantPool} instance associated with the given type
   *
   * @param type the type to get the constant pool for
   * @return the associated {@linkplain ConstantPool} instance
   * @throws IllegalArgumentException if the type does not support constant pools
   */
  @SuppressWarnings("unchecked")
  ConstantPool forType(JFRType type) {
    if (!type.hasConstantPool()) {
      throw new IllegalArgumentException();
    }
    return constantPoolMap.computeIfAbsent(type, this::newConstantPool);
  }

  int size() {
    return constantPoolMap.size();
  }

  @Override
  public Iterator<ConstantPool> iterator() {
    return getOrderedPools().iterator();
  }

  @Override
  public void forEach(Consumer<? super ConstantPool> action) {
    getOrderedPoolsStream().forEach(action);
  }

  @Override
  public Spliterator<ConstantPool> spliterator() {
    return getOrderedPools().spliterator();
  }

  /**
   * The pool instances need to be sorted in a way that if a value from pool P1 is using value(s)
   * from pool P2 then P2 must come before P1.
   *
   * @return sorted pool instances
   */
  @SuppressWarnings("unchecked")
  private Stream<ConstantPool> getOrderedPoolsStream() {
    return constantPoolMap
        .entrySet()
        .stream()
        .sorted((e1, e2) -> e1 == e2 ? 0 : e1.getKey().isUsedBy(e2.getKey()) ? -1 : 1)
        .map(Map.Entry::getValue);
  }

  private List<ConstantPool> getOrderedPools() {
    return getOrderedPoolsStream().collect(Collectors.toList());
  }

  private ConstantPool newConstantPool(JFRType type) {
    return new ConstantPool(type);
  }
}
