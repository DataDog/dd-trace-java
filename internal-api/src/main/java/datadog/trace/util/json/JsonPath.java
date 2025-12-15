package datadog.trace.util.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class JsonPath {

  public static class Builder {
    private final List<Segment> segments;

    public Builder() {
      super();
      segments = new ArrayList<>();
      segments.add(Segment.Singleton.ROOT);
    }

    public static Builder start() {
      return new Builder();
    }

    public Builder name(String name) {
      segments.add(new Segment.Name(name));
      return this;
    }

    public Builder index(int index) {
      segments.add(new Segment.Index(index));
      return this;
    }

    public Builder anyDesc() {
      segments.add(Segment.Singleton.DESCENDANT);
      return this;
    }

    public Builder anyChild() {
      segments.add(Segment.Singleton.WILDCARD);
      return this;
    }

    public JsonPath build() {
      return new JsonPath(segments);
    }
  }

  private final Segment[] segments;

  private JsonPath(Collection<Segment> segments) {
    this.segments = segments.toArray(new Segment[0]);
  }

  public boolean matches(PathCursor pathCursor) {
    int i = segments.length - 1;
    int j = pathCursor.length();
    while (i >= 0 && j >= 0 && segments[i].matches(jsonPathSegment(pathCursor, j))) {
      i--;
      j--;
      if (segments[i + 1] == Segment.Singleton.DESCENDANT) {
        int prevSearchSegmentPos = findPrevDescendantSegment(this, i);
        int blockSize = i - prevSearchSegmentPos;
        int offset2 = j - blockSize + 2;
        while (offset2 > 0
            && !matchPathBlock(this, prevSearchSegmentPos + 1, pathCursor, offset2, blockSize)) {
          offset2--;
        }
        i = prevSearchSegmentPos;
        j = offset2 - 1;
      }
    }
    return j < 0 && i < 0;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (Segment segment : segments) {
      sb.append(segment);
    }
    return sb.toString();
  }

  private boolean matchPathBlock(
      JsonPath pattern, int offset1, PathCursor pathCursor, int offset2, int blockSize) {
    for (int i = 0; i < blockSize; i++) {
      if (!pattern.segments[offset1 + i].matches(jsonPathSegment(pathCursor, offset2 + i))) {
        return false;
      }
    }
    return true;
  }

  private Object jsonPathSegment(PathCursor pathCursor, int index) {
    if (index == 0) {
      return null;
    }
    return pathCursor.get(index - 1);
  }

  private int findPrevDescendantSegment(JsonPath pattern, int from) {
    int i = from - 1;
    while (i > 0) {
      if (pattern.segments[i] == Segment.Singleton.DESCENDANT) {
        return i;
      }
      i--;
    }
    return i;
  }

  private abstract static class Segment {

    protected boolean matches(Object value) {
      return this == Singleton.WILDCARD
          || this == Singleton.DESCENDANT
          || this == Singleton.ROOT && value == null;
    }

    private static final class Singleton extends Segment {
      private static final Segment ROOT = new Singleton("$");
      private static final Segment WILDCARD = new Singleton("[*]");
      private static final Segment DESCENDANT = new Singleton("..");

      private final String repr;

      private Singleton(String repr) {
        super();
        this.repr = repr;
      }

      @Override
      public String toString() {
        return repr;
      }
    }

    private static final class Name extends Segment {
      private final String name;

      public Name(String name) {
        super();
        this.name = name;
      }

      @Override
      protected boolean matches(Object value) {
        return value instanceof String && value.equals(name);
      }

      @Override
      public String toString() {
        return "['" + name + "']";
      }
    }

    private static final class Index extends Segment {
      private final int index;

      public Index(int index) {
        super();
        this.index = index;
      }

      @Override
      protected boolean matches(Object value) {
        return value instanceof Integer && ((Integer) value) == index;
      }

      @Override
      public String toString() {
        return "[" + index + "]";
      }
    }
  }
}
