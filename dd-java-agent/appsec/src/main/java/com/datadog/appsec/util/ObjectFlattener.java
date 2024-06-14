package com.datadog.appsec.util;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.Moshi;

/**
 * This class provides a utility method to flatten an object into a Map. The object's fields are
 * used as keys in the Map, and the field values are used as values. The field values are
 * recursively flattened if they are custom objects.
 */
public class ObjectFlattener {

  private static final JsonAdapter<Object> JSON_ADAPTER =
      new Moshi.Builder().build().adapter(Object.class);

  /**
   * Flattens an object into a Map.
   *
   * @param obj the object to flatten
   * @return the flattened object as a Map, or the original object if it's a primitive type or a
   *     Collection or a Map. Returns null if the input object is null or if it cannot be flattened.
   */
  public static Object flatten(Object obj) {
    try {
      return JSON_ADAPTER.toJsonValue(obj);
    } catch (JsonDataException e) {
      return null;
    }
  }
}
