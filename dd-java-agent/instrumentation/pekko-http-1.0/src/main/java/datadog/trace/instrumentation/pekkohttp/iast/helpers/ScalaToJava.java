package datadog.trace.instrumentation.pekkohttp.iast.helpers;

import java.util.ArrayList;
import scala.collection.Iterator;
import scala.collection.immutable.List;
import scala.collection.immutable.Map;

// do not use JavaConverters, as they changed in an ABI-incompatible way in Scala 2.13
public class ScalaToJava {
  public static java.util.List<String> keySetAsCollection(Map<String, ?> m) {
    scala.collection.immutable.Set<String> keys = m.keySet();
    java.util.List<String> keysAsCollection = new ArrayList<>(keys.size());
    Iterator<String> keysIterator = keys.iterator();
    while (keysIterator.hasNext()) {
      keysAsCollection.add(keysIterator.next());
    }
    return keysAsCollection;
  }

  public static <T> java.util.List<T> listAsList(List<T> l) {
    java.util.List<T> asJavaList = new ArrayList<>(l.size());
    Iterator<T> iterator = l.iterator();
    while (iterator.hasNext()) {
      asJavaList.add(iterator.next());
    }
    return asJavaList;
  }
}
