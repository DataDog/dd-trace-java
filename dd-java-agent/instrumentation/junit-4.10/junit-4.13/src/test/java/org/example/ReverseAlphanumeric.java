package org.example;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.junit.runner.Description;
import org.junit.runner.manipulation.Ordering;

public class ReverseAlphanumeric implements Ordering.Factory {
  private static final Comparator<Description> COMPARATOR =
      (o1, o2) -> o2.getDisplayName().compareTo(o1.getDisplayName());

  public static class ReverseAlphanumericOrdering extends Ordering {
    @Override
    public List<Description> orderItems(Collection<Description> descriptions) {
      List<Description> sorted = new ArrayList<>(descriptions);
      sorted.sort(COMPARATOR);
      return sorted;
    }
  }

  @Override
  public Ordering create(Ordering.Context context) {
    return new ReverseAlphanumericOrdering();
  }
}
