package datadog.trace.bootstrap.debugger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.agent.JsonSnapshotSerializer;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CapturedContextCaptureExpressionsTest {
  @BeforeAll
  static void initSerializer() {
    // Ensure freeze() has a serializer to produce strValue
    DebuggerContext.initValueSerializer(new JsonSnapshotSerializer());
  }

  @Test
  void addGetAndFreezeCaptureExpressions() {
    CapturedContext ctx = new CapturedContext();

    CapturedContext.CapturedValue exprVal =
        CapturedContext.CapturedValue.of("expr1", Object.class.getTypeName(), 42);

    // addCaptureExpression should lazily init the map and put the value
    ctx.addCaptureExpression(exprVal);

    // getCaptureExpressions should return the map (covering getter line)
    Map<String, CapturedContext.CapturedValue> exprs = ctx.getCaptureExpressions();
    assertNotNull(exprs);
    assertTrue(exprs.containsKey("expr1"));
    assertSame(exprVal, exprs.get("expr1"));

    // freeze should only freeze captureExpressions branch when present
    ctx.freeze(new TimeoutChecker(Duration.of(50, ChronoUnit.MILLIS)));

    CapturedContext.CapturedValue frozen = exprs.get("expr1");
    assertNotNull(frozen.getStrValue());
    assertNull(frozen.getValue()); // value released after serialization
  }
}
