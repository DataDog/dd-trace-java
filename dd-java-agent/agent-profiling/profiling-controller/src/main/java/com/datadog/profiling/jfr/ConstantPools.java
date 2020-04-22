package com.datadog.profiling.jfr;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class ConstantPools implements Iterable<ConstantPool> {
  private final Map<Type, ConstantPool> constantPoolMap = new HashMap<>();

  @SuppressWarnings("unchecked")
  ConstantPool forType(Type type) {
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

  @SuppressWarnings("unchecked")
  private Stream<ConstantPool> getOrderedPoolsStream() {
    return constantPoolMap
        .entrySet()
        .stream()
        .sorted((e1, e2) -> e1 == e2 ? 0 : isUsedBy(e1.getKey(), e2.getKey()) ? -1 : 1)
        .map(Map.Entry::getValue);
  }

  private List<ConstantPool> getOrderedPools() {
    return getOrderedPoolsStream().collect(Collectors.toList());
  }

  private boolean isUsedBy(Type type1, Type type2) {
    return isUsedBy(type1, type2, new HashSet<>());
  }

  private boolean isUsedBy(Type type1, Type type2, HashSet<Type> track) {
    if (!track.add(type2)) {
      return false;
    }
    for (TypedField typedField : type2.getFields()) {
      if (typedField.getType().equals(type1)) {
        return true;
      }
      if (isUsedBy(type1, typedField.getType(), track)) {
        return true;
      }
    }
    return false;
  }

  private ConstantPool newConstantPool(Type type) {
    return new ConstantPool(type);
  }
}
