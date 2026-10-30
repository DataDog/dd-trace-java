package datadog.trace.instrumentation.jackson.blackbird;

import static java.util.Arrays.asList;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class Corpus {
  private Corpus() {}

  static final class Case {
    final String name;
    final Object value;
    final TypeReference<?> type;
    final Class<?> view;

    Case(String name, Object value, TypeReference<?> type, Class<?> view) {
      this.name = name;
      this.value = value;
      this.type = type;
      this.view = view;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  static final class Input {
    final String name;
    final String json;
    final TypeReference<?> type;

    Input(String name, String json, TypeReference<?> type) {
      this.name = name;
      this.json = json;
      this.type = type;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  static final class Setup {
    final String name;
    final Consumer<ObjectMapper> configure;

    Setup(String name, Consumer<ObjectMapper> configure) {
      this.name = name;
      this.configure = configure;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  static List<Setup> setups() {
    return asList(
        new Setup("modules-only", Corpus::registerDataModules),
        new Setup(
            "app-configured",
            m -> {
              registerDataModules(m);
              m.setSerializationInclusion(JsonInclude.Include.NON_NULL);
              m.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
              m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
              m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
              m.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
              m.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
              m.addMixIn(ThirdPartyType.class, ThirdPartyMixin.class);
            }),
        new Setup(
            "snake-case",
            m -> {
              registerDataModules(m);
              m.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
              m.addMixIn(ThirdPartyType.class, ThirdPartyMixin.class);
            }),
        new Setup(
            "fields-only",
            m -> {
              registerDataModules(m);
              m.setVisibility(
                  m.getSerializationConfig()
                      .getDefaultVisibilityChecker()
                      .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                      .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                      .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                      .withSetterVisibility(JsonAutoDetect.Visibility.NONE));
            }));
  }

  static void registerDataModules(ObjectMapper m) {
    m.registerModule(new JavaTimeModule());
    m.registerModule(new Jdk8Module());
  }

  static List<Case> cases() {
    final List<Case> cases = new ArrayList<>();
    cases.add(new Case("small-bean", SmallBean.sample(1), new TypeReference<SmallBean>() {}, null));
    cases.add(
        new Case("small-bean-nulls", new SmallBean(), new TypeReference<SmallBean>() {}, null));
    cases.add(
        new Case("primitives", Primitives.sample(), new TypeReference<Primitives>() {}, null));
    cases.add(
        new Case(
            "public-fields", PublicFields.sample(), new TypeReference<PublicFields>() {}, null));
    cases.add(
        new Case(
            "private-fields", PrivateFields.sample(), new TypeReference<PrivateFields>() {}, null));
    cases.add(new Case("annotated", Annotated.sample(), new TypeReference<Annotated>() {}, null));
    cases.add(
        new Case(
            "creator",
            new CreatorBean("abc", 42, List.of("x", "y")),
            new TypeReference<CreatorBean>() {},
            null));
    cases.add(new Case("record", RecordDto.sample(), new TypeReference<RecordDto>() {}, null));
    cases.add(
        new Case(
            "builder",
            BuilderDto.builder().id(7).name("built").tags(List.of("a", "b")).build(),
            new TypeReference<BuilderDto>() {},
            null));
    cases.add(
        new Case(
            "polymorphic-list",
            List.of(new Circle(1.5), new Square(2, "red"), new Circle(0)),
            new TypeReference<List<Shape>>() {},
            null));
    cases.add(
        new Case(
            "polymorphic-field",
            new Drawing("d1", new Square(3, null)),
            new TypeReference<Drawing>() {},
            null));
    cases.add(
        new Case(
            "view-public",
            ViewBean.sample(),
            new TypeReference<ViewBean>() {},
            Views.Public.class));
    cases.add(
        new Case(
            "view-internal",
            ViewBean.sample(),
            new TypeReference<ViewBean>() {},
            Views.Internal.class));
    cases.add(new Case("view-none", ViewBean.sample(), new TypeReference<ViewBean>() {}, null));
    cases.add(
        new Case(
            "mixin-target", ThirdPartyType.sample(), new TypeReference<ThirdPartyType>() {}, null));
    cases.add(
        new Case("optionals", OptionalBean.sample(), new TypeReference<OptionalBean>() {}, null));
    cases.add(
        new Case(
            "optionals-empty", new OptionalBean(), new TypeReference<OptionalBean>() {}, null));
    cases.add(new Case("java-time", TimeBean.sample(), new TypeReference<TimeBean>() {}, null));
    cases.add(
        new Case("collections", Collections.sample(), new TypeReference<Collections>() {}, null));
    cases.add(new Case("enums", EnumBean.sample(), new TypeReference<EnumBean>() {}, null));
    cases.add(new Case("defaults", new Defaults(), new TypeReference<Defaults>() {}, null));
    cases.add(new Case("inheritance", Child.sample(), new TypeReference<Child>() {}, null));
    cases.add(new Case("any-props", AnyProps.sample(), new TypeReference<AnyProps>() {}, null));
    cases.add(new Case("unwrapped", Unwrapping.sample(), new TypeReference<Unwrapping>() {}, null));
    cases.add(
        new Case(
            "generic-page",
            new Page<>(List.of(SmallBean.sample(1), SmallBean.sample(2)), 2, 10),
            new TypeReference<Page<SmallBean>>() {},
            null));
    cases.add(new Case("medium-order", Order.sample(1), new TypeReference<Order>() {}, null));
    final List<SmallBean> list = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      list.add(SmallBean.sample(i));
    }
    cases.add(new Case("list-of-100", list, new TypeReference<List<SmallBean>>() {}, null));
    cases.add(
        new Case(
            "map-of-beans",
            new LinkedHashMap<>(Map.of("one", SmallBean.sample(1))),
            new TypeReference<Map<String, SmallBean>>() {},
            null));
    return cases;
  }

  static List<Input> inputs() {
    return asList(
        new Input(
            "unknown-property",
            "{\"name\":\"n\",\"surprise\":1}",
            new TypeReference<SmallBean>() {}),
        new Input(
            "type-mismatch", "{\"count\":\"not-a-number\"}", new TypeReference<SmallBean>() {}),
        new Input(
            "null-for-primitive", "{\"i\":null,\"z\":null}", new TypeReference<Primitives>() {}),
        new Input("missing-fields", "{}", new TypeReference<Defaults>() {}),
        new Input(
            "explicit-nulls",
            "{\"name\":null,\"count\":null,\"tags\":null,\"price\":null}",
            new TypeReference<SmallBean>() {}),
        new Input("creator-missing-arg", "{\"code\":\"z\"}", new TypeReference<CreatorBean>() {}),
        new Input("record-partial", "{\"id\":5}", new TypeReference<RecordDto>() {}),
        new Input(
            "unknown-subtype", "[{\"kind\":\"hexagon\"}]", new TypeReference<List<Shape>>() {}),
        new Input("bad-enum", "{\"status\":\"NOPE\"}", new TypeReference<EnumBean>() {}),
        new Input(
            "ignored-on-read",
            "{\"secret\":\"s\",\"id\":\"i\"}",
            new TypeReference<Annotated>() {}),
        new Input(
            "coercions",
            "{\"i\":\"12\",\"l\":3.0,\"d\":\"1e3\",\"z\":\"true\",\"c\":\"q\"}",
            new TypeReference<Primitives>() {}),
        new Input("not-an-object", "[1,2]", new TypeReference<SmallBean>() {}),
        new Input("truncated", "{\"name\":\"n\"", new TypeReference<SmallBean>() {}));
  }

  enum Status {
    ACTIVE,
    SUSPENDED,
    CLOSED;

    @Override
    public String toString() {
      return name().toLowerCase();
    }
  }

  enum Level {
    LOW("l"),
    HIGH("h");

    private final String code;

    Level(String code) {
      this.code = code;
    }

    @JsonValue
    public String code() {
      return code;
    }

    @Override
    public String toString() {
      return code;
    }
  }

  public static class SmallBean {
    private String name;
    private int count;
    private long total;
    private double ratio;
    private boolean active;
    private Integer optionalCount;
    private BigDecimal price;
    private List<String> tags;
    private Status status;
    private Address address;

    static SmallBean sample(int i) {
      SmallBean b = new SmallBean();
      b.name = "name-" + i;
      b.count = i;
      b.total = 10_000_000_000L + i;
      b.ratio = i / 3.0;
      b.active = i % 2 == 0;
      b.optionalCount = i % 3 == 0 ? null : i * 7;
      b.price = new BigDecimal("19.99").add(BigDecimal.valueOf(i));
      b.tags = List.of("t" + i, "common");
      b.status = Status.values()[i % 3];
      b.address = Address.sample(i);
      return b;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public int getCount() {
      return count;
    }

    public void setCount(int count) {
      this.count = count;
    }

    public long getTotal() {
      return total;
    }

    public void setTotal(long total) {
      this.total = total;
    }

    public double getRatio() {
      return ratio;
    }

    public void setRatio(double ratio) {
      this.ratio = ratio;
    }

    public boolean isActive() {
      return active;
    }

    public void setActive(boolean active) {
      this.active = active;
    }

    public Integer getOptionalCount() {
      return optionalCount;
    }

    public void setOptionalCount(Integer optionalCount) {
      this.optionalCount = optionalCount;
    }

    public BigDecimal getPrice() {
      return price;
    }

    public void setPrice(BigDecimal price) {
      this.price = price;
    }

    public List<String> getTags() {
      return tags;
    }

    public void setTags(List<String> tags) {
      this.tags = tags;
    }

    public Status getStatus() {
      return status;
    }

    public void setStatus(Status status) {
      this.status = status;
    }

    public Address getAddress() {
      return address;
    }

    public void setAddress(Address address) {
      this.address = address;
    }
  }

  public static class Address {
    private String street;
    private String city;
    private String zip;
    private String country;

    static Address sample(int i) {
      Address a = new Address();
      a.street = i + " Main St";
      a.city = "Springfield";
      a.zip = String.format("%05d", i);
      a.country = "US";
      return a;
    }

    public String getStreet() {
      return street;
    }

    public void setStreet(String street) {
      this.street = street;
    }

    public String getCity() {
      return city;
    }

    public void setCity(String city) {
      this.city = city;
    }

    public String getZip() {
      return zip;
    }

    public void setZip(String zip) {
      this.zip = zip;
    }

    public String getCountry() {
      return country;
    }

    public void setCountry(String country) {
      this.country = country;
    }
  }

  public static class Primitives {
    private int i;
    private long l;
    private double d;
    private float f;
    private short s;
    private byte b;
    private char c;
    private boolean z;
    private Integer boxedI;
    private Long boxedL;
    private Boolean boxedZ;
    private byte[] bytes;
    private BigInteger big;
    private UUID uuid;

    static Primitives sample() {
      Primitives p = new Primitives();
      p.i = Integer.MIN_VALUE;
      p.l = Long.MAX_VALUE;
      p.d = Double.MIN_VALUE;
      p.f = 3.25f;
      p.s = Short.MAX_VALUE;
      p.b = -1;
      p.c = 'x';
      p.z = true;
      p.boxedI = 0;
      p.boxedL = null;
      p.boxedZ = Boolean.FALSE;
      p.bytes = new byte[] {0, 1, 2, (byte) 255};
      p.big = new BigInteger("123456789012345678901234567890");
      p.uuid = new UUID(1, 2);
      return p;
    }

    public int getI() {
      return i;
    }

    public void setI(int i) {
      this.i = i;
    }

    public long getL() {
      return l;
    }

    public void setL(long l) {
      this.l = l;
    }

    public double getD() {
      return d;
    }

    public void setD(double d) {
      this.d = d;
    }

    public float getF() {
      return f;
    }

    public void setF(float f) {
      this.f = f;
    }

    public short getS() {
      return s;
    }

    public void setS(short s) {
      this.s = s;
    }

    public byte getB() {
      return b;
    }

    public void setB(byte b) {
      this.b = b;
    }

    public char getC() {
      return c;
    }

    public void setC(char c) {
      this.c = c;
    }

    public boolean isZ() {
      return z;
    }

    public void setZ(boolean z) {
      this.z = z;
    }

    public Integer getBoxedI() {
      return boxedI;
    }

    public void setBoxedI(Integer boxedI) {
      this.boxedI = boxedI;
    }

    public Long getBoxedL() {
      return boxedL;
    }

    public void setBoxedL(Long boxedL) {
      this.boxedL = boxedL;
    }

    public Boolean getBoxedZ() {
      return boxedZ;
    }

    public void setBoxedZ(Boolean boxedZ) {
      this.boxedZ = boxedZ;
    }

    public byte[] getBytes() {
      return bytes;
    }

    public void setBytes(byte[] bytes) {
      this.bytes = bytes;
    }

    public BigInteger getBig() {
      return big;
    }

    public void setBig(BigInteger big) {
      this.big = big;
    }

    public UUID getUuid() {
      return uuid;
    }

    public void setUuid(UUID uuid) {
      this.uuid = uuid;
    }
  }

  public static class PublicFields {
    public String id;
    public int n;
    public long big;
    public boolean flag;
    public double score;
    public List<Integer> values;
    public Address address;

    static PublicFields sample() {
      PublicFields p = new PublicFields();
      p.id = "pf";
      p.n = 3;
      p.big = -5L;
      p.flag = true;
      p.score = 0.1 + 0.2;
      p.values = List.of(1, 2, 3);
      p.address = Address.sample(9);
      return p;
    }
  }

  @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
  public static class PrivateFields {
    private String id;
    private int n;
    private boolean flag;
    private final List<String> items = new ArrayList<>();

    static PrivateFields sample() {
      PrivateFields p = new PrivateFields();
      p.id = "private";
      p.n = 11;
      p.flag = true;
      p.items.add("a");
      return p;
    }
  }

  @JsonPropertyOrder({"id", "display_name"})
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Annotated {
    private String id;
    private String displayName;
    private String secret;
    private String nullable;

    static Annotated sample() {
      Annotated a = new Annotated();
      a.id = "a1";
      a.displayName = "Display";
      a.secret = "hunter2";
      return a;
    }

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    @JsonProperty("display_name")
    public String getDisplayName() {
      return displayName;
    }

    @JsonProperty("display_name")
    public void setDisplayName(String displayName) {
      this.displayName = displayName;
    }

    @JsonIgnore
    public String getSecret() {
      return secret;
    }

    @JsonIgnore
    public void setSecret(String secret) {
      this.secret = secret;
    }

    public String getNullable() {
      return nullable;
    }

    public void setNullable(String nullable) {
      this.nullable = nullable;
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getComputed() {
      return id + "/" + displayName;
    }
  }

  public static final class CreatorBean {
    private final String code;
    private final int amount;
    private final List<String> labels;

    @JsonCreator
    public CreatorBean(
        @JsonProperty("code") String code,
        @JsonProperty("amount") int amount,
        @JsonProperty("labels") List<String> labels) {
      this.code = code;
      this.amount = amount;
      this.labels = labels;
    }

    public String getCode() {
      return code;
    }

    public int getAmount() {
      return amount;
    }

    public List<String> getLabels() {
      return labels;
    }
  }

  public record RecordDto(
      long id, String name, boolean enabled, List<String> roles, Address address, Double weight) {
    static RecordDto sample() {
      return new RecordDto(99L, "rec", true, List.of("admin"), Address.sample(4), null);
    }
  }

  @JsonDeserialize(builder = BuilderDto.Builder.class)
  public static final class BuilderDto {
    private final int id;
    private final String name;
    private final List<String> tags;

    private BuilderDto(Builder b) {
      this.id = b.id;
      this.name = b.name;
      this.tags = b.tags;
    }

    static Builder builder() {
      return new Builder();
    }

    public int getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    public List<String> getTags() {
      return tags;
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
      private int id;
      private String name;
      private List<String> tags;

      public Builder id(int id) {
        this.id = id;
        return this;
      }

      public Builder name(String name) {
        this.name = name;
        return this;
      }

      public Builder tags(List<String> tags) {
        this.tags = tags;
        return this;
      }

      public BuilderDto build() {
        return new BuilderDto(this);
      }
    }
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Circle.class, name = "circle"),
    @JsonSubTypes.Type(value = Square.class, name = "square")
  })
  public abstract static class Shape {}

  public static class Circle extends Shape {
    private double radius;

    public Circle() {}

    Circle(double radius) {
      this.radius = radius;
    }

    public double getRadius() {
      return radius;
    }

    public void setRadius(double radius) {
      this.radius = radius;
    }
  }

  public static class Square extends Shape {
    private int side;
    private String color;

    public Square() {}

    Square(int side, String color) {
      this.side = side;
      this.color = color;
    }

    public int getSide() {
      return side;
    }

    public void setSide(int side) {
      this.side = side;
    }

    public String getColor() {
      return color;
    }

    public void setColor(String color) {
      this.color = color;
    }
  }

  public static class Drawing {
    public String id;
    public Shape main;

    public Drawing() {}

    Drawing(String id, Shape main) {
      this.id = id;
      this.main = main;
    }
  }

  static final class Views {
    static class Public {}

    static class Internal extends Public {}
  }

  public static class ViewBean {
    @JsonView(Views.Public.class)
    public String name;

    @JsonView(Views.Internal.class)
    public String internalNote;

    @JsonView(Views.Internal.class)
    private int score;

    public String unviewed;

    static ViewBean sample() {
      ViewBean v = new ViewBean();
      v.name = "view";
      v.internalNote = "note";
      v.score = 77;
      v.unviewed = "always?";
      return v;
    }

    @JsonView(Views.Internal.class)
    public int getScore() {
      return score;
    }

    public void setScore(int score) {
      this.score = score;
    }
  }

  public static class ThirdPartyType {
    private String identifier;
    private String internal;
    private int version;

    static ThirdPartyType sample() {
      ThirdPartyType t = new ThirdPartyType();
      t.identifier = "3p";
      t.internal = "hide me";
      t.version = 2;
      return t;
    }

    public String getIdentifier() {
      return identifier;
    }

    public void setIdentifier(String identifier) {
      this.identifier = identifier;
    }

    public String getInternal() {
      return internal;
    }

    public void setInternal(String internal) {
      this.internal = internal;
    }

    public int getVersion() {
      return version;
    }

    public void setVersion(int version) {
      this.version = version;
    }
  }

  @JsonIgnoreProperties({"internal"})
  abstract static class ThirdPartyMixin {
    @JsonProperty("id")
    abstract String getIdentifier();

    @JsonProperty("id")
    abstract void setIdentifier(String identifier);
  }

  public static class OptionalBean {
    private Optional<String> nickname = Optional.empty();
    private Optional<Address> address = Optional.empty();
    private OptionalInt rank = OptionalInt.empty();

    static OptionalBean sample() {
      OptionalBean o = new OptionalBean();
      o.nickname = Optional.of("nick");
      o.address = Optional.of(Address.sample(2));
      o.rank = OptionalInt.of(3);
      return o;
    }

    public Optional<String> getNickname() {
      return nickname;
    }

    public void setNickname(Optional<String> nickname) {
      this.nickname = nickname;
    }

    public Optional<Address> getAddress() {
      return address;
    }

    public void setAddress(Optional<Address> address) {
      this.address = address;
    }

    public OptionalInt getRank() {
      return rank;
    }

    public void setRank(OptionalInt rank) {
      this.rank = rank;
    }
  }

  public static class TimeBean {
    public Instant instant;
    public LocalDate date;
    public LocalDateTime dateTime;
    public OffsetDateTime offset;
    public Duration duration;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy/MM/dd")
    public LocalDate formatted;

    static TimeBean sample() {
      TimeBean t = new TimeBean();
      t.instant = Instant.ofEpochSecond(1_700_000_000L, 123_456_789);
      t.date = LocalDate.of(2024, 2, 29);
      t.dateTime = LocalDateTime.of(2024, 1, 2, 3, 4, 5, 6_000_000);
      t.offset = OffsetDateTime.of(2024, 5, 6, 7, 8, 9, 0, ZoneOffset.UTC);
      t.duration = Duration.ofMillis(90_061_001L);
      t.formatted = LocalDate.of(1999, 12, 31);
      return t;
    }
  }

  public static class Collections {
    public Map<String, List<Integer>> mapOfLists;
    public int[] ints;
    public String[] strings;
    public long[] emptyLongs;
    public Set<String> set;
    public List<List<String>> nested;
    public Map<Integer, Address> intKeys;
    public List<Object> mixed;
    public Map<String, Object> untyped;

    static Collections sample() {
      Collections c = new Collections();
      c.mapOfLists = new LinkedHashMap<>();
      c.mapOfLists.put("a", List.of(1, 2));
      c.mapOfLists.put("b", List.of());
      c.ints = new int[] {3, 1, 2};
      c.strings = new String[] {"x", null, ""};
      c.emptyLongs = new long[0];
      c.set = new LinkedHashSet<>(List.of("s1", "s2"));
      c.nested = List.of(List.of("n"), List.of());
      c.intKeys = new LinkedHashMap<>();
      c.intKeys.put(1, Address.sample(1));
      c.mixed = asList(1, "two", 3.0, true, null);
      c.untyped = new LinkedHashMap<>();
      c.untyped.put("k", List.of(Map.of("deep", 1)));
      return c;
    }
  }

  public static class EnumBean {
    public Status status;
    public Level level;
    public List<Status> history;
    public EnumMap<Status, Integer> counts;

    static EnumBean sample() {
      EnumBean e = new EnumBean();
      e.status = Status.SUSPENDED;
      e.level = Level.HIGH;
      e.history = List.of(Status.ACTIVE, Status.CLOSED);
      e.counts = new EnumMap<>(Status.class);
      e.counts.put(Status.ACTIVE, 4);
      return e;
    }
  }

  public static class Defaults {
    private int retries = 3;
    private String mode = "auto";
    private boolean enabled = true;
    private List<String> list = new ArrayList<>(List.of("default"));
    private Integer boxed;

    public int getRetries() {
      return retries;
    }

    public void setRetries(int retries) {
      this.retries = retries;
    }

    public String getMode() {
      return mode;
    }

    public void setMode(String mode) {
      this.mode = mode;
    }

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public List<String> getList() {
      return list;
    }

    public void setList(List<String> list) {
      this.list = list;
    }

    public Integer getBoxed() {
      return boxed;
    }

    public void setBoxed(Integer boxed) {
      this.boxed = boxed;
    }
  }

  public static class Parent {
    private String parentField;
    protected int shared;

    public String getParentField() {
      return parentField;
    }

    public void setParentField(String parentField) {
      this.parentField = parentField;
    }

    public int getShared() {
      return shared;
    }

    public void setShared(int shared) {
      this.shared = shared;
    }
  }

  public static class Child extends Parent {
    private String childField;

    static Child sample() {
      Child c = new Child();
      c.setParentField("p");
      c.setShared(5);
      c.childField = "c";
      return c;
    }

    public String getChildField() {
      return childField;
    }

    public void setChildField(String childField) {
      this.childField = childField;
    }

    @Override
    public int getShared() {
      return super.getShared();
    }
  }

  public static class AnyProps {
    private String id;
    private final Map<String, Object> other = new LinkedHashMap<>();

    static AnyProps sample() {
      AnyProps a = new AnyProps();
      a.id = "any";
      a.other.put("x", 1);
      a.other.put("y", List.of("z"));
      return a;
    }

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    @JsonAnyGetter
    public Map<String, Object> getOther() {
      return other;
    }

    @JsonAnySetter
    public void set(String key, Object value) {
      other.put(key, value);
    }
  }

  public static class Unwrapping {
    public String id;

    @JsonUnwrapped(prefix = "home_")
    public Address home;

    static Unwrapping sample() {
      Unwrapping u = new Unwrapping();
      u.id = "u";
      u.home = Address.sample(3);
      return u;
    }
  }

  public static class Page<T> {
    private List<T> items;
    private int total;
    private int size;

    public Page() {}

    Page(List<T> items, int total, int size) {
      this.items = items;
      this.total = total;
      this.size = size;
    }

    public List<T> getItems() {
      return items;
    }

    public void setItems(List<T> items) {
      this.items = items;
    }

    public int getTotal() {
      return total;
    }

    public void setTotal(int total) {
      this.total = total;
    }

    public int getSize() {
      return size;
    }

    public void setSize(int size) {
      this.size = size;
    }
  }

  public static class Order {
    public String orderId;
    public long createdAt;
    public String currency;
    public BigDecimal subtotal;
    public BigDecimal tax;
    public BigDecimal shipping;
    public BigDecimal total;
    public Status status;
    public boolean gift;
    public String giftMessage;
    public String channel;
    public String couponCode;
    public int itemCount;
    public Customer customer;
    public Address shippingAddress;
    public Address billingAddress;
    public List<Line> lines;
    public Map<String, String> attributes;

    static Order sample(int i) {
      Order o = new Order();
      o.orderId = "ord-" + i;
      o.createdAt = 1_700_000_000_000L + i;
      o.currency = "USD";
      o.subtotal = new BigDecimal("100.00");
      o.tax = new BigDecimal("8.25");
      o.shipping = new BigDecimal("5.00");
      o.total = new BigDecimal("113.25");
      o.status = Status.ACTIVE;
      o.gift = i % 2 == 1;
      o.giftMessage = o.gift ? "happy birthday" : null;
      o.channel = "web";
      o.couponCode = null;
      o.customer = Customer.sample(i);
      o.shippingAddress = Address.sample(i);
      o.billingAddress = Address.sample(i + 1);
      o.lines = new ArrayList<>();
      for (int l = 0; l < 3; l++) {
        o.lines.add(Line.sample(l));
      }
      o.itemCount = o.lines.size();
      o.attributes = new LinkedHashMap<>();
      o.attributes.put("source", "campaign-" + i);
      return o;
    }
  }

  public static class Customer {
    public long id;
    public String firstName;
    public String lastName;
    public String email;
    public String phone;
    public boolean vip;
    public int loyaltyPoints;
    public LocalDate since;
    public List<String> segments;

    static Customer sample(int i) {
      Customer c = new Customer();
      c.id = 1000 + i;
      c.firstName = "Ada";
      c.lastName = "Lovelace";
      c.email = "ada" + i + "@example.com";
      c.phone = "+1-555-0100";
      c.vip = i % 5 == 0;
      c.loyaltyPoints = i * 13;
      c.since = LocalDate.of(2020, 1, 1).plusDays(i);
      c.segments = List.of("retail", "newsletter");
      return c;
    }
  }

  public static class Line {
    public String sku;
    public String description;
    public int quantity;
    public BigDecimal unitPrice;
    public BigDecimal discount;
    public double weightKg;
    public boolean backordered;

    static Line sample(int i) {
      Line l = new Line();
      l.sku = "SKU-" + i;
      l.description = "Item number " + i;
      l.quantity = i + 1;
      l.unitPrice = new BigDecimal("9.99");
      l.discount = i == 0 ? null : BigDecimal.ONE;
      l.weightKg = 0.25 * (i + 1);
      l.backordered = i == 2;
      return l;
    }
  }
}
