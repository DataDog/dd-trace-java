package com.datadog.iast.model.json;

import com.datadog.iast.taint.TaintedObject;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.util.List;

public class TaintedObjectEncoding {

  private static final JsonAdapter<List<TaintedObject>> LIST_ADAPTER =
      new Moshi.Builder()
          .add(new TaintedObjectAdapter())
          .build()
          .adapter(Types.newParameterizedType(List.class, TaintedObject.class));

  private static final JsonAdapter<TaintedObject> ADAPTER =
      new Moshi.Builder().add(new TaintedObjectAdapter()).build().adapter(TaintedObject.class);

  public static String toJson(final List<TaintedObject> value) {
    return LIST_ADAPTER.toJson(value);
  }

  public static String toJson(final TaintedObject value) {
    return ADAPTER.toJson(value);
  }
}
