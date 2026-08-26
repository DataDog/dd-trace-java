package datadog.trace.bootstrap.debugger.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Properties;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class WellKnownClassesTest {

  @Test
  public void synchronizedWrappersAreNotSafe() {
    assertFalse(WellKnownClasses.isSafe(new Vector<>()));
    assertFalse(WellKnownClasses.isSafe(new Stack<>()));
    assertFalse(WellKnownClasses.isSafe(new Hashtable<>()));
    assertFalse(WellKnownClasses.isSafe(new Properties()));
    assertFalse(WellKnownClasses.isSafe(Collections.synchronizedCollection(new ArrayList<>())));
    assertFalse(WellKnownClasses.isSafe(Collections.synchronizedList(new ArrayList<>())));
    assertFalse(WellKnownClasses.isSafe(Collections.synchronizedList(new LinkedList<>())));
    assertFalse(WellKnownClasses.isSafe(Collections.synchronizedSet(new HashSet<>())));
    assertFalse(WellKnownClasses.isSafe(Collections.synchronizedSortedSet(new TreeSet<>())));
    assertFalse(WellKnownClasses.isSafe(Collections.synchronizedNavigableSet(new TreeSet<>())));
    assertFalse(WellKnownClasses.isSafe(Collections.synchronizedMap(new HashMap<>())));
    assertFalse(WellKnownClasses.isSafe(Collections.synchronizedSortedMap(new TreeMap<>())));
    assertFalse(WellKnownClasses.isSafe(Collections.synchronizedNavigableMap(new TreeMap<>())));
  }

  @Test
  public void plainCollectionsAreSafe() {
    assertTrue(WellKnownClasses.isSafe(new ArrayList<>()));
    assertTrue(WellKnownClasses.isSafe(new HashSet<>()));
    assertTrue(WellKnownClasses.isSafe(new HashMap<>()));
    assertTrue(WellKnownClasses.isSafe(new ConcurrentHashMap<>()));
    assertTrue(WellKnownClasses.isSafe(Collections.emptyList()));
    assertTrue(WellKnownClasses.isSafe(Collections.unmodifiableMap(new HashMap<>())));
  }
}
