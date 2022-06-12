package datadog.trace.agent.tooling.context;

import static datadog.trace.bootstrap.FieldBackedContextStores.getContextStoreId;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;

/** Manages the persistent state associated with {@link ShouldInjectFieldsMatcher}. */
class ShouldInjectFieldsState {

  // this map will contain as many entries as there are unique
  // context store keys, so can't get very big
  private static final ConcurrentHashMap<String, Boolean> KEY_TYPE_IS_CLASS =
      new ConcurrentHashMap<>();

  // this map will contain entries for any root type that we wanted to field-inject
  // but were not able to - either because it was explicitly excluded, or because we
  // failed to field-inject as the type was already loaded
  private static final ConcurrentHashMap<String, BitSet> EXCLUDED_STORE_IDS_BY_TYPE =
      new ConcurrentHashMap<String, BitSet>();

  private ShouldInjectFieldsState() {}

  /**
   * Searches for the earliest class in the hierarchy to have fields injected under the given key.
   */
  public static String findInjectionTarget(TypeDescription typeDescription, String keyType) {
    // precondition: typeDescription must be a sub type of the key class
    // verifying this isn't free so the caller (in the same package) is trusted

    // The flag takes 3 values:
    // true: the key type is a class, so should be the injection target
    // false: the key type is an interface, so we need to find the class
    // closest to java.lang.Object which implements the key type
    // null: we don't know yet because we haven't seen the key type before
    Boolean keyTypeIsClass = KEY_TYPE_IS_CLASS.get(keyType);
    if (null != keyTypeIsClass && keyTypeIsClass) {
      // if we already know the key type is a class,
      // we must inject into that class.
      return keyType;
    }
    // then we don't know it's a class so need to
    // follow the type's ancestry to find out
    TypeDefinition superClass = typeDescription.getSuperClass();
    String implementingClass = typeDescription.getName();
    Map<String, Boolean> visitedInterfaces = new HashMap<>();
    while (null != superClass) {
      String superClassName = superClass.asErasure().getName();
      if (null == keyTypeIsClass && keyType.equals(superClassName)) {
        // short circuit the search with this key type next time
        KEY_TYPE_IS_CLASS.put(keyType, true);
        return keyType;
      }
      if (hasKeyInterface(superClass, keyType, visitedInterfaces)) {
        // then the key type must be an interface
        if (null == keyTypeIsClass) {
          KEY_TYPE_IS_CLASS.put(keyType, false);
          keyTypeIsClass = false;
        }
        implementingClass = superClassName;
      }
      superClass = superClass.getSuperClass();
    }
    return implementingClass;
  }

  private static boolean hasKeyInterface(
      TypeDefinition typeDefinition, String keyType, Map<String, Boolean> visitedInterfaces) {
    for (TypeDefinition iface : typeDefinition.getInterfaces()) {
      String interfaceName = iface.asErasure().getName();
      if (keyType.equals(interfaceName)) {
        return true;
      }
      Boolean foundKeyInterface = visitedInterfaces.get(interfaceName);
      if (Boolean.TRUE.equals(foundKeyInterface)) {
        return true; // already know this will lead to the key
      }
      if (null == foundKeyInterface) {
        // avoid cycle issues by assuming we won't find the key
        visitedInterfaces.put(interfaceName, false);
        if (hasKeyInterface(iface, keyType, visitedInterfaces)) {
          visitedInterfaces.put(interfaceName, true); // update assumption
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Keep track of which stores (per-type) were explicitly excluded or we failed to field-inject.
   * This is used to decide when we can't apply certain store optimizations ahead of loading.
   */
  public static void excludeInjectedField(
      String instrumentedType, String keyType, String valueType) {
    int storeId = getContextStoreId(keyType, valueType);
    BitSet excludedStoreIdsForType = EXCLUDED_STORE_IDS_BY_TYPE.get(instrumentedType);
    if (null == excludedStoreIdsForType) {
      BitSet tempStoreIds = new BitSet();
      tempStoreIds.set(storeId);
      excludedStoreIdsForType =
          EXCLUDED_STORE_IDS_BY_TYPE.putIfAbsent(instrumentedType, tempStoreIds);
    }
    // if we didn't get there first then add this store to the existing set
    if (null != excludedStoreIdsForType) {
      synchronized (excludedStoreIdsForType) {
        excludedStoreIdsForType.set(storeId);
      }
    }
  }

  /**
   * Scans the class hierarchy to see if it matches any known context-key types which implies it has
   * an injected field somewhere. This avoids having to record successful field-injections which can
   * add up to a lot of entries. Unfortunately we can't check for the marker interface because the
   * type won't have that at this point.
   *
   * <p>At the same time we collect which context stores failed to be field-injected in the class
   * hierarchy. This tells us when to redirect store requests to the weak-map vs delegating to the
   * superclass.
   *
   * <p>Assumes the type has already been processed by ShouldInjectFieldsMatcher.
   */
  public static boolean hasInjectedField(TypeDefinition typeDefinition, BitSet excludedStoreIds) {
    Set<String> visitedInterfaces = new HashSet<>();
    while (null != typeDefinition) {
      String className = typeDefinition.asErasure().getName();
      BitSet excludedStoreIdsForType = EXCLUDED_STORE_IDS_BY_TYPE.get(className);
      if (null != excludedStoreIdsForType) {
        synchronized (excludedStoreIdsForType) {
          excludedStoreIds.or(excludedStoreIdsForType);
        }
      } else if (KEY_TYPE_IS_CLASS.containsKey(className)
          || impliesInjectedField(typeDefinition, visitedInterfaces)) {
        return true;
      }
      typeDefinition = typeDefinition.getSuperClass();
    }
    return false;
  }

  /** Scans transitive interfaces to see if any match known context-key types. */
  private static boolean impliesInjectedField(
      final TypeDefinition typeDefinition, final Set<String> visitedInterfaces) {
    for (TypeDefinition iface : typeDefinition.getInterfaces()) {
      String interfaceName = iface.asErasure().getName();
      if (KEY_TYPE_IS_CLASS.containsKey(interfaceName)
          || (visitedInterfaces.add(interfaceName)
              && impliesInjectedField(iface, visitedInterfaces))) {
        return true;
      }
    }
    return false;
  }
}
