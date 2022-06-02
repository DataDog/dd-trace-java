package datadog.trace.bootstrap.debugger;

import com.datadog.debugger.agent.DenyListHelper;
import datadog.trace.api.Platform;
import java.time.Duration;
import java.util.AbstractCollection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.EncryptedPrivateKeyInfo;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ValueConverterTest {

  @BeforeEach
  public void setup() {
    DebuggerContext.initClassFilter(new DenyListHelper(null));
  }

  @Test
  public void basic() {
    ValueConverter converter = new ValueConverter();
    String value = converter.convert(null);
    Assert.assertEquals("null", value);
    value = converter.convert("foobar");
    Assert.assertEquals("foobar", value);
    value = converter.convert(new Bar());
    Assert.assertEquals("Bar(f1=1, f2=2)", value);
    value = converter.convert(new Foo1());
    Assert.assertEquals("Foo1(f3=3, bar=Bar(f1=1, f2=2))", value);
    value = converter.convert(new Foo2());
    Assert.assertEquals("Foo2(phantom1=99, f4=4)", value);
  }

  @Test
  public void denied() throws Exception {
    ValueConverter converter = new ValueConverter();
    Object obj = new Object();
    String value = converter.convert(obj);
    Assert.assertEquals(obj.toString(), value);
    value = converter.convert(new java.security.spec.X509EncodedKeySpec("key".getBytes()));
    Assert.assertEquals("X509EncodedKeySpec(<DENIED>)", value);
    value = converter.convert(new EncryptedPrivateKeyInfo("MD5", "foo".getBytes()));
    if (Platform.isJavaVersionAtLeast(16)) {
      Assert.assertEquals(
          "EncryptedPrivateKeyInfo(algid=<NOT_CAPTURED>, keyAlg=<NOT_CAPTURED>, encryptedData=<NOT_CAPTURED>, encoded=<NOT_CAPTURED>)",
          value);
    } else {
      System.out.println("denied EncryptedPrivateKeyInfo: " + value);
      Assert.assertTrue(value.contains("algid=AlgorithmId(<DENIED>"));
    }
  }

  @Test
  public void collections() {
    ValueConverter converter = new ValueConverter();
    String value = converter.convert(new ArrayList<>());
    Assert.assertEquals("[]", value);
    Collection<Function<Collection<?>, AbstractCollection<?>>> collectionSuppliers =
        Arrays.asList(
            ArrayList::new,
            LinkedList::new,
            HashSet::new,
            ArrayDeque::new,
            LinkedBlockingDeque::new,
            ConcurrentLinkedDeque::new);
    for (Function<Collection<?>, AbstractCollection<?>> supplier : collectionSuppliers) {
      AbstractCollection<?> collection = supplier.apply(Arrays.asList(new Bar(), new Bar()));
      value = converter.convert(collection);
      Assert.assertEquals("[Bar(f1=1, f2=2), Bar(f1=1, f2=2)]", value);
    }
    value = converter.convert(Arrays.asList(new Foo1(), new Foo1()));
    Assert.assertEquals(
        "[Foo1(f3=3, bar=datadog.trace.bootstrap.debugger.ValueConverterTest$Bar), Foo1(f3=3, bar=datadog.trace.bootstrap.debugger.ValueConverterTest$Bar)]",
        value);
    converter = new ValueConverter(limitRefDepth(2));
    value = converter.convert(Arrays.asList(new Foo1(), new Foo1()));
    Assert.assertEquals(
        "[Foo1(f3=3, bar=Bar(f1=1, f2=2)), Foo1(f3=3, bar=Bar(f1=1, f2=2))]", value);
    converter = new ValueConverter(limitCollectionSize(3));
    value = converter.convert(Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));
    Assert.assertEquals("[1, 2, 3, ...]", value);
  }

  @Test
  public void selfCollection() {
    ValueConverter converter = new ValueConverter();
    ArrayList<Object> list = new ArrayList<>();
    list.add(list);
    String value = converter.convert(list);
    Assert.assertEquals("[(this Collection)]", value);
  }

  @Test
  public void maps() {
    ValueConverter converter = new ValueConverter();
    String value = converter.convert(new HashMap<>());
    Assert.assertEquals("{}", value);
    Collection<Supplier<Map<? super Object, ? super Object>>> mapSupplierCollection =
        Arrays.asList(HashMap::new, LinkedHashMap::new, ConcurrentHashMap::new);
    for (Supplier<Map<? super Object, ? super Object>> supplier : mapSupplierCollection) {
      Map<?, ?> map = newMap(supplier, new Bar(), new Bar(), new Bar(), new Bar());
      value = converter.convert(map);
      Assert.assertEquals(
          "{Bar(f1=1, f2=2)=Bar(f1=1, f2=2), Bar(f1=1, f2=2)=Bar(f1=1, f2=2)}", value);
    }
    Map<Object, Object> map = new HashMap<>();
    converter = new ValueConverter(limitRefDepth(2));
    map.put(new Foo1(), new Foo1());
    value = converter.convert(map);
    Assert.assertEquals("{Foo1(f3=3, bar=Bar(f1=1, f2=2))=Foo1(f3=3, bar=Bar(f1=1, f2=2))}", value);
    map.clear();
    converter = new ValueConverter(limitCollectionSize(3));
    map.put("key1", "value1");
    map.put("key2", "value2");
    map.put("key3", "value3");
    map.put("key4", "value4");
    value = converter.convert(map);
    Matcher matcher = Pattern.compile("key\\d=value\\d").matcher(value);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    Assert.assertEquals(3, count);
    Assert.assertTrue(value.endsWith(", ...}"));
  }

  @Test
  public void selfMap() {
    ValueConverter converter = new ValueConverter();
    Map<Object, Object> map = new HashMap<>();
    map.put(map, map);
    String value = converter.convert(map);
    Assert.assertEquals("{(this Map)=(this Map)}", value);
  }

  private <T> Map<? super Object, ? super Object> newMap(
      Supplier<Map<? super Object, ? super Object>> mapSupplier, T... values) {
    Map<? super Object, ? super Object> map = mapSupplier.get();
    for (int i = 0; i < values.length; i += 2) {
      Object key = values[i];
      Object value = values[i + 1];
      map.put(key, value);
    }
    return map;
  }

  @Test
  public void arrays() {
    ValueConverter converter = new ValueConverter();
    String value = converter.convert(new Object[] {new Bar(), new Bar()});
    Assert.assertEquals("[Bar(f1=1, f2=2), Bar(f1=1, f2=2)]", value);
    value = converter.convert(new Object[] {new Foo1(), new Foo1()});
    Assert.assertEquals(
        "[Foo1(f3=3, bar=datadog.trace.bootstrap.debugger.ValueConverterTest$Bar), Foo1(f3=3, bar=datadog.trace.bootstrap.debugger.ValueConverterTest$Bar)]",
        value);
    converter = new ValueConverter(limitRefDepth(2));
    value = converter.convert(new Object[] {new Foo1(), new Foo1()});
    Assert.assertEquals(
        "[Foo1(f3=3, bar=Bar(f1=1, f2=2)), Foo1(f3=3, bar=Bar(f1=1, f2=2))]", value);
    converter = new ValueConverter(limitCollectionSize(3));
    value = converter.convert(new String[] {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10"});
    Assert.assertEquals("[1, 2, 3, ...]", value);
  }

  @Test
  public void selfArray() {
    ValueConverter converter = new ValueConverter();
    Object[] array = new Object[10];
    array[0] = array;
    String value = converter.convert(array);
    Assert.assertEquals("[[...], null, null, null, null, null, null, null, null, null]", value);
  }

  @Test
  public void primitiveArrays() {
    ValueConverter converter = new ValueConverter(limitCollectionSize(7));
    int[] intArray = new int[4096];
    for (int i = 0; i < intArray.length; i++) {
      intArray[i] = i;
    }
    String value = converter.convert(intArray);
    Assert.assertEquals("[0, 1, 2, 3, 4, 5, 6, ...]", value);
    byte[] buffer = new byte[] {-1, (byte) 255, 0, 1};
    value = converter.convert(buffer);
    Assert.assertEquals("[-1, -1, 0, 1]", value);
    char[] empty = new char[0];
    value = converter.convert(empty);
    Assert.assertEquals("[]", value);
  }

  @Test
  public void largeCollections() {
    ValueConverter converter = new ValueConverter();
    Object[] largeArray = new Object[1000];
    Arrays.fill(largeArray, new Bar());
    String value = converter.convert(largeArray);
    Assert.assertEquals(1705, value.length());
    value = converter.convert(new ArrayList<>(Arrays.asList(largeArray)));
    Assert.assertEquals(1705, value.length());
    Map<Object, Object> map = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      map.put(String.format("Foo%03d", i), new Bar());
    }
    value = converter.convert(map);
    Assert.assertEquals(2405, value.length());
  }

  @Test
  public void composite() {
    ValueConverter.Limits limits =
        new ValueConverter.Limits(2, 100, 300, ValueConverter.DEFAULT_FIELD_COUNT);
    ValueConverter converter = new ValueConverter(limits);
    String value = converter.convert(new CompositeBar());
    System.out.println(value);
    Assert.assertEquals(
        "CompositeBar(f1=Foo1(f3=3, bar=Bar(f1=1, f2=2)), f2=Foo2(phantom1=99, f4=4), list=[Foo1(f3=3, bar=datadog.trace.bootstrap.debugger.ValueConverterTest$Bar), Foo1(f3=3, bar=datadog.trace.bootstrap.debugger.ValueConverterTest$Bar)])",
        value);
    limits = new ValueConverter.Limits(3, 100, 200, ValueConverter.DEFAULT_FIELD_COUNT);
    converter = new ValueConverter(limits);
    value = converter.convert(new CompositeBar());
    Assert.assertEquals(
        "CompositeBar(f1=Foo1(f3=3, bar=Bar(f1=1, f2=2)), f2=Foo2(phantom1=99, f4=4), list=[Foo1(f3=3, bar=Bar(f1=1, f2=2)), Foo1(f3=3, bar=Bar(f1=1, f2=2))])",
        value);
  }

  @Test
  public void maxLength() {
    ValueConverter.Limits limits =
        new ValueConverter.Limits(1, 100, 6, ValueConverter.DEFAULT_FIELD_COUNT);
    ValueConverter valueConverter = new ValueConverter(limits);
    String value = valueConverter.convert("0123456789");
    Assert.assertEquals("012345...", value);
    value = valueConverter.convert(new Bar());
    Assert.assertEquals("Bar(f1...)", value);
    limits = new ValueConverter.Limits(1, 100, 20, ValueConverter.DEFAULT_FIELD_COUNT);
    valueConverter = new ValueConverter(limits);
    value = valueConverter.convert(new Foo1());
    Assert.assertEquals("Foo1(f3=3, bar=Bar(f...)", value);
    value =
        valueConverter.convert(
            new String[] {
              "keggskjgskgjskgjsklgjsdkgjsklgsjgklsjglkjgksjgl",
              "a",
              "b",
              "c",
              "d",
              "e",
              "f",
              "g",
              "h",
              "i",
              "j",
              "k"
            });
    Assert.assertEquals("[keggskjgskgjskgjsklg..., a, b, c, d, e, f, g, h, i, j, k]", value);
  }

  @Test
  public void adverselyToString() {
    ValueConverter valueConverter = new ValueConverter();
    String value = valueConverter.convert(new AdverselyToString());
    Assert.assertEquals(
        "AdverselyToString(largeStrings=[abcdefghijklmnopqrstuvwxyz0, abcdefghijklmnopqrstuvwxyz1, abcdefghijklmnopqrstuvwxyz2, abcdefghijklmnopqrstuvwxyz3, abcdefghijklmnopqrstuvwxyz4, abcdefghijklmnopqrstuvwxyz5, abcdefghijklmnopqrstuvwxyz6, abcdefghijklmnopqrst...)",
        value);
  }

  @Test
  public void maxFieldCount() {
    ValueConverter valueConverter = new ValueConverter(new ValueConverter.Limits(1, 100, 255, 1));
    String value = valueConverter.convert(new CompositeBar());
    Assert.assertEquals("CompositeBar(f1=Foo1(f3=3))", value);
  }

  private ValueConverter.Limits limitRefDepth(int maxReferenceDepth) {
    return new ValueConverter.Limits(
        maxReferenceDepth,
        ValueConverter.DEFAULT_COLLECTION_SIZE,
        ValueConverter.DEFAULT_LENGTH,
        ValueConverter.DEFAULT_FIELD_COUNT);
  }

  private ValueConverter.Limits limitCollectionSize(int maxCollectionSize) {
    return new ValueConverter.Limits(
        ValueConverter.DEFAULT_REFERENCE_DEPTH,
        maxCollectionSize,
        ValueConverter.DEFAULT_LENGTH,
        ValueConverter.DEFAULT_FIELD_COUNT);
  }

  static class Bar {
    private int f1 = 1;
    private int f2 = 2;
  }

  static class Foo1 {
    private int f3 = 3;
    private Bar bar = new Bar();
  }

  static class Foo2 extends Foo1 {
    private transient int phantom1 = 99;
    private int f4 = 4;
  }

  static class CompositeBar {
    private Foo1 f1 = new Foo1();
    private Foo2 f2 = new Foo2();
    private List<Foo1> list = new ArrayList(Arrays.asList(new Foo1(), new Foo1()));
  }

  static class AdverselyToString {
    private List<String> largeStrings = new ArrayList<>();

    public AdverselyToString() {
      for (int i = 0; i < 1_000_000; i++) {
        largeStrings.add("abcdefghijklmnopqrstuvwxyz" + i);
      }
    }

    @Override
    public String toString() {
      LockSupport.parkNanos(Duration.ofMinutes(1).toNanos());
      return "AdverselyExpensiveToString{" + "largeStrings=" + largeStrings + '}';
    }
  }
}
