package com.datadog.profiling.jfr;

import java.util.Comparator;

final class TypeByUsageComparator implements Comparator<Type> {
  public static final TypeByUsageComparator INSTANCE = new TypeByUsageComparator();

  @Override
  public int compare(Type t1, Type t2) {
    return t1 == t2 ? 0 : t1.isUsedBy(t2) ? -1 : 1;
  }
}
