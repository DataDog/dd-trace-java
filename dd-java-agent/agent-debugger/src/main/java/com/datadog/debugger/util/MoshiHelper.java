package com.datadog.debugger.util;

import com.datadog.debugger.agent.ProbeDefinition;
import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.agent.Where;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.ValueScript;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Helper for creating Moshi instances with the right adapters depending on the context */
public class MoshiHelper {

  public static Moshi createMoshiConfig() {
    ProbeCondition.ProbeConditionJsonAdapter probeConditionJsonAdapter =
        new ProbeCondition.ProbeConditionJsonAdapter();
    return new Moshi.Builder()
        .add(ProbeCondition.class, probeConditionJsonAdapter)
        .add(DebuggerScript.class, probeConditionJsonAdapter)
        .add(ValueScript.class, new ValueScript.ValueScriptAdapter())
        .add(Where.SourceLine[].class, new Where.SourceLineAdapter())
        .add(ProbeDefinition.Tag[].class, new ProbeDefinition.TagAdapter())
        .build();
  }

  public static Moshi createMoshiSnapshot() {
    return new Moshi.Builder()
        .add(new SnapshotJsonFactory())
        .add(
            DebuggerScript.class,
            new ProbeCondition.ProbeConditionJsonAdapter()) // ProbeDetails in Snapshot
        .build();
  }

  public static Moshi createMoshiProbeStatus() {
    return new Moshi.Builder().add(new ProbeStatus.DiagnosticsFactory()).build();
  }

  public static JsonAdapter<Map<String, Object>> createGenericAdapter() {
    ParameterizedType type = Types.newParameterizedType(Map.class, String.class, Object.class);
    return new Moshi.Builder().build().adapter(type);
  }

  private static class SnapshotJsonFactory implements JsonAdapter.Factory {
    @Override
    public JsonAdapter<?> create(Type type, Set<? extends Annotation> set, Moshi moshi) {
      if (Types.equals(type, Snapshot.Captures.class)) {
        return new CapturesAdapter(moshi);
      }
      return null;
    }
  }

  private static class CapturesAdapter extends JsonAdapter<Snapshot.Captures> {
    private final Moshi moshi;
    private final JsonAdapter<Snapshot.CapturedContext> capturedContextAdapter;
    private final JsonAdapter<Map<Integer, Snapshot.CapturedContext>> linesAdapter;
    private final JsonAdapter<List<Snapshot.CapturedThrowable>> caughtExceptionsAdapter;

    public CapturesAdapter(Moshi moshi) {
      this.moshi = moshi;
      capturedContextAdapter = moshi.adapter(Snapshot.CapturedContext.class);
      linesAdapter =
          moshi.adapter(
              Types.newParameterizedType(Map.class, Integer.class, Snapshot.CapturedContext.class));
      caughtExceptionsAdapter =
          moshi.adapter(Types.newParameterizedType(List.class, Snapshot.CapturedThrowable.class));
    }

    @Override
    public Snapshot.Captures fromJson(JsonReader jsonReader) throws IOException {
      jsonReader.beginObject();
      Snapshot.Captures captures = new Snapshot.Captures();
      while (jsonReader.hasNext()) {
        String name = jsonReader.nextName();
        switch (name) {
          case "entry":
            captures.setEntry(capturedContextAdapter.fromJson(jsonReader));
            break;
          case "return":
            captures.setReturn(capturedContextAdapter.fromJson(jsonReader));
            break;
          case "lines":
            Map<Integer, Snapshot.CapturedContext> map = linesAdapter.fromJson(jsonReader);
            if (map != null) {
              map.forEach(captures::addLine);
            }
            break;
          case "caughtExceptions":
            List<Snapshot.CapturedThrowable> capturedThrowables =
                caughtExceptionsAdapter.fromJson(jsonReader);
            capturedThrowables.forEach(captures::addCaughtException);
            break;
          default:
            throw new IllegalArgumentException("Unknown field name for Captures object: " + name);
        }
      }
      jsonReader.endObject();
      return captures;
    }

    @Override
    public void toJson(JsonWriter jsonWriter, Snapshot.Captures captures) throws IOException {
      jsonWriter.beginObject();
      jsonWriter.name("entry");
      capturedContextAdapter.toJson(jsonWriter, captures.getEntry());
      jsonWriter.name("lines");
      linesAdapter.toJson(jsonWriter, captures.getLines());
      jsonWriter.name("return");
      capturedContextAdapter.toJson(jsonWriter, captures.getReturn());
      jsonWriter.name("caughtExceptions");
      caughtExceptionsAdapter.toJson(jsonWriter, captures.getCaughtExceptions());
      jsonWriter.endObject();
    }
  }
}
