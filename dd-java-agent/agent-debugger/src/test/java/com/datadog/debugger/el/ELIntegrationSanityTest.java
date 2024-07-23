package com.datadog.debugger.el;

import static com.datadog.debugger.agent.CapturedSnapshotTest.getFields;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadog.debugger.agent.JsonSnapshotSerializer;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

public class ELIntegrationSanityTest {
  static class Name {
    private String value;

    public Name(String value) {
      this.value = value;
    }
  }

  static class Person {
    private static final String C1 = "constant1";
    private static final int C2 = 42;
    private static List<String> list = new ArrayList<>();
    private String strVal = "strval";
    private int intVal = 24;
    private Map<String, String> mapVal = new HashMap<>();
    private Object[] objArray = new Object[] {new AtomicLong()};
    private Name name = new Name("name");
  }

  @Test
  void extractAfterEl() throws IllegalAccessException {
    JsonSnapshotSerializer serializer =
        new JsonSnapshotSerializer(); // Mockito.spy(new JsonSnapshotSerializer());
    DebuggerContext.initValueSerializer(serializer);
    Person p = new Person();
    // set the limit not to follow references to fields
    Limits initialLimits = new Limits(2, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
    // create new captured context
    CapturedContext capturedContext = new CapturedContext();
    CapturedContext.CapturedValue thisValue =
        CapturedContext.CapturedValue.of(
            "this",
            Person.class.getName(),
            p,
            initialLimits.maxReferenceDepth,
            initialLimits.maxCollectionSize,
            initialLimits.maxLength,
            initialLimits.maxFieldCount);
    capturedContext.addArguments(new CapturedContext.CapturedValue[] {thisValue});

    // '.name.value' is not present in the snapshot - it needs to be retrieved via reflection
    Value<?> val = DSL.getMember(DSL.ref("name"), "value").evaluate(capturedContext);
    // make sure the nested field was properly resolved
    assertEquals(p.name.value, val.getValue());

    // freeze the captured context
    capturedContext.freeze(new TimeoutChecker(Duration.of(1, ChronoUnit.SECONDS)));

    // after freezing the original value is removed and only the serialized json representation
    // remains
    Map<String, CapturedContext.CapturedValue> thisFields =
        getFields(capturedContext.getArguments().get("this"));
    Map<String, CapturedContext.CapturedValue> name =
        (Map<String, CapturedContext.CapturedValue>) thisFields.get("name").getValue();
    assertEquals(p.name.value, name.get("value").getValue());
  }
}
