package datadog.trace.payloadtags.json;

public final class JsonPath {

  public static class Builder {
    public static final int DEFAULT_CAPACITY_IN_SEGMENTS = 8;
    private JsonPath jsonPath;

    public static Builder start() {
      return start(DEFAULT_CAPACITY_IN_SEGMENTS);
    }

    public static Builder start(int capacity) {
      return start(new JsonPath(capacity));
    }

    public static Builder start(JsonPath jsonPath) {
      Builder builder = new Builder();
      builder.jsonPath = jsonPath;
      return builder;
    }

    public Builder name(String name) {
      // TODO check if there is already a name segment to reduce allocations
      jsonPath.push(new Segment.Name(name));
      return this;
    }

    public Builder index(int index) {
      // TODO check if there is already such a segment to reduce allocations
      jsonPath.push(new Segment.Index(index));
      return this;
    }

    public Builder search() {
      jsonPath.push(Segment.Singleton.DESCENDANT);
      return this;
    }

    public Builder any() {
      jsonPath.push(Segment.Singleton.WILDCARD);
      return this;
    }

    protected JsonPath jsonPath() { // TODO maybe expose a match method instead???
      return this.jsonPath;
    }

    public JsonPath build() {
      // TODO assert it doesn't end with a dot
      JsonPath result = this.jsonPath;
      this.jsonPath = null;
      return result;
    }

    public void beginArray() {
      index(0);
    }

    public void endArray() {
      assert jsonPath.peek() instanceof Segment.Index;
      jsonPath.size--;
    }

    public void endValue() {
      Segment current = jsonPath.peek();
      if (current instanceof Segment.Name) {
        // pop key when within an object
        jsonPath.size--;
      } else if (current instanceof Segment.Index) {
        // inc index when within an array
        ((Segment.Index) current).inc();
      }
    }

    public Builder copy() {
      JsonPath copy = new JsonPath(this.jsonPath);
      return start(copy);
    }

    public int length() {
      return jsonPath.size - 1;
    }
  }

  private Segment[] segments;
  private int size;

  private JsonPath(int capacity) {
    assert capacity > 0;
    segments = new Segment[capacity];
    segments[0] = Segment.Singleton.ROOT;
    size = 1;
  }

  private JsonPath(JsonPath copyFrom) {
    segments = new Segment[copyFrom.segments.length];
    size = copyFrom.size;
    for (int i = 0; i < copyFrom.size; i++) {
      segments[i] = copyFrom.segments[i].copy();
    }
  }

  public boolean matches(JsonPath that) {
    int i = this.size - 1;
    int j = that.size - 1;
    while (i >= 0 && j >= 0 && this.get(i).matches(that.get(j))) {
      i--;
      j--;
      if (this.get(i + 1) == Segment.Singleton.DESCENDANT) {
        int prevSearchSegmentPos = findPrevSearchSegment(this, i);
        int blockSize = i - prevSearchSegmentPos;
        int offset2 = j - blockSize + 2;
        while (offset2 > 0
            && !matchPathBlock(this, prevSearchSegmentPos + 1, that, offset2, blockSize)) {
          offset2--;
        }
        i = prevSearchSegmentPos;
        j = offset2 - 1;
      }
    }
    return j < 0 && i < 0;
  }

  public String dotted(StringBuilder prefix) {
    int len = prefix.length();
    for (int i = 0; i < size; i++) {
      segments[i].printTo(prefix);
    }
    String result = prefix.toString();
    prefix.setLength(len);
    return result;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < size; i++) {
      sb.append(segments[i]);
    }
    return sb.toString();
  }

  private boolean matchPathBlock(
      JsonPath path1, int offset1, JsonPath path2, int offset2, int blockSize) {
    for (int i = 0; i < blockSize; i++) {
      if (!path1.get(offset1 + i).matches(path2.get(offset2 + i))) {
        return false;
      }
    }
    return true;
  }

  private int findPrevSearchSegment(JsonPath path, int from) {
    int i = from - 1;
    while (i > 0) {
      if (path.get(i) == Segment.Singleton.DESCENDANT) {
        return i;
      }
      i--;
    }
    return i;
  }

  private Segment get(int i) {
    return segments[i];
  }

  private Segment peek() {
    return segments[size - 1];
  }

  private void push(Segment segment) {
    if (segments.length < size + 1) {
      Segment[] newArray = new Segment[segments.length * 2];
      System.arraycopy(segments, 0, newArray, 0, segments.length);
      segments = newArray;
    }
    segments[size++] = segment;
  }

  private abstract static class Segment {

    protected boolean matches(Segment that) {
      return this == that || this == Singleton.WILDCARD || this == Singleton.DESCENDANT;
    }

    protected abstract Segment copy();

    private void printTo(StringBuilder sb) {
      if (this == Singleton.ROOT) {
        return;
      }
      sb.append('.');
      if (this == Singleton.WILDCARD) {
        sb.append('*');
      } else if (this instanceof Name) {
        sb.append(((Name) this).name.replace(".", "\\."));
      } else if (this instanceof Index) {
        sb.append(((Index) this).index);
      }
    }

    private static final class Singleton extends Segment {
      private static final Segment ROOT = new Singleton("$");
      private static final Segment WILDCARD = new Singleton("[*]");
      private static final Segment DESCENDANT = new Singleton("..");

      private final String repr;

      public Singleton(String repr) {
        super();
        this.repr = repr;
      }

      @Override
      protected Segment copy() {
        return this;
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
      protected boolean matches(Segment that) {
        return that instanceof Name && ((Name) that).name.equals(name);
      }

      @Override
      protected Segment copy() {
        return new Name(name);
      }

      @Override
      public String toString() {
        return "['" + name + "']";
      }
    }

    private static final class Index extends Segment {
      private int index;

      public Index(int index) {
        super();
        this.index = index;
      }

      @Override
      protected boolean matches(Segment that) {
        return that instanceof Index && ((Index) that).index == index;
      }

      @Override
      protected Segment copy() {
        return new Index(index);
      }

      @Override
      public String toString() {
        return "[" + index + "]";
      }

      public void inc() {
        index++;
      }
    }
  }
}
