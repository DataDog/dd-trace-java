package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.BaseHash;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link R2dbcSqlCommentInjector#inject}. These assert the ACTUAL injected DBM
 * static metadata comment, so a broken injector is caught independently of span assertions.
 */
class R2dbcSqlCommentInjectorTest extends AbstractInstrumentationTest {

  @Test
  void injectsStaticMetadataCommentWhenDbmFull() {
    injectSysConfig("dbm.propagation.mode", "full");

    String injected =
        R2dbcSqlCommentInjector.inject(
            "SELECT * FROM items", "orders", "postgresql", "db.internal", "shop");

    // Comment is prepended as /*...*/ <sql>; only static metadata (no traceparent) for R2DBC.
    assertTrue(injected.startsWith("/*"), "expected a leading DBM comment, got: " + injected);
    assertTrue(injected.endsWith("*/ SELECT * FROM items"), "unexpected tail: " + injected);
    assertTrue(injected.contains("dddbs='orders'"), "missing db service: " + injected);
    assertTrue(injected.contains("dddb='shop'"), "missing db name: " + injected);
    assertTrue(injected.contains("ddh='db.internal'"), "missing hostname: " + injected);
    // No per-execution trace context is injected at statement-creation time for R2DBC.
    assertTrue(!injected.contains("traceparent="), "unexpected traceparent: " + injected);
  }

  @Test
  void doesNotInjectWhenDbmDisabled() {
    injectSysConfig("dbm.propagation.mode", "disabled");

    String sql = "SELECT * FROM items";
    assertEquals(sql, R2dbcSqlCommentInjector.inject(sql, "orders", "postgresql", "h", "shop"));
  }

  @Test
  void doesNotDoubleInjectAnExistingDdComment() {
    injectSysConfig("dbm.propagation.mode", "full");

    String once = R2dbcSqlCommentInjector.inject("SELECT 1", "orders", "postgresql", "h", "shop");
    String twice = R2dbcSqlCommentInjector.inject(once, "orders", "postgresql", "h", "shop");
    assertEquals(once, twice, "comment should not be injected twice");
  }

  @Test
  void dynamicServiceInjectsBaseHashInComment() {
    injectSysConfig("dbm.propagation.mode", "dynamic_service");
    BaseHash.updateBaseHash(123456789L);

    String injected = R2dbcSqlCommentInjector.inject("SELECT 1", "orders", "h2", "h", "shop");

    assertTrue(injected.contains("ddsh='123456789'"), "missing base hash: " + injected);
  }
}
