package datadog.trace.instrumentation.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.BaseHash;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link R2dbcSqlCommentInjector#inject}. These assert the ACTUAL injected DBM
 * static metadata comment, so a broken injector is caught independently of span assertions.
 *
 * <p>One class per propagation mode: {@link R2dbcDecorator} reads the mode once when its statics
 * initialize, and {@code forkedTest} forks per test class ({@code forkEvery = 1}).
 */
@WithConfig(key = "dbm.propagation.mode", value = "full")
class R2dbcSqlCommentInjectorForkedTest extends AbstractInstrumentationTest {

  @Test
  void injectsStaticMetadataCommentWhenDbmFull() {
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
  void doesNotDoubleInjectAnExistingDdComment() {
    String once = R2dbcSqlCommentInjector.inject("SELECT 1", "orders", "postgresql", "h", "shop");
    String twice = R2dbcSqlCommentInjector.inject(once, "orders", "postgresql", "h", "shop");
    assertEquals(once, twice, "comment should not be injected twice");
  }

  @Test
  void appendsForCallStatements() {
    String injected =
        R2dbcSqlCommentInjector.inject("call my_proc(1)", "orders", "postgresql", "h", "shop");
    assertTrue(injected.startsWith("call my_proc(1) /*"), "expected appended comment: " + injected);
    assertTrue(injected.endsWith("*/"), "unexpected tail: " + injected);
  }

  @Test
  void appendsAfterPostgresPlanHint() {
    String sql = "/*+ SeqScan(items) */ SELECT * FROM items";
    String injected = R2dbcSqlCommentInjector.inject(sql, "orders", "postgresql", "h", "shop");
    assertTrue(injected.startsWith(sql + " /*"), "hint must stay first: " + injected);
  }

  @Test
  void prependsHintLikeCommentForNonPostgres() {
    String sql = "/*+ INDEX(items idx) */ SELECT * FROM items";
    String injected = R2dbcSqlCommentInjector.inject(sql, "orders", "mysql", "h", "shop");
    assertTrue(injected.endsWith("*/ " + sql), "expected prepended comment: " + injected);
  }

  @Test
  void alwaysAppendKeepsClosingSemicolon() {
    String injected =
        R2dbcSqlCommentInjector.inject("SELECT 1;", "orders", "postgresql", "h", "shop", true);
    assertTrue(injected.startsWith("SELECT 1 /*"), "expected appended comment: " + injected);
    assertTrue(injected.endsWith("*/;"), "semicolon should stay last: " + injected);
  }

  @Test
  void doesNotDoubleAppend() {
    String once =
        R2dbcSqlCommentInjector.inject("SELECT 1;", "orders", "postgresql", "h", "shop", true);
    String twice = R2dbcSqlCommentInjector.inject(once, "orders", "postgresql", "h", "shop", true);
    assertEquals(once, twice, "comment should not be appended twice");
  }
}

/** Verifies {@link R2dbcSqlCommentInjector#inject} is a no-op when DBM propagation is disabled. */
@WithConfig(key = "dbm.propagation.mode", value = "disabled")
class R2dbcSqlCommentInjectorDisabledForkedTest extends AbstractInstrumentationTest {

  @Test
  void doesNotInjectWhenDbmDisabled() {
    String sql = "SELECT * FROM items";
    assertEquals(sql, R2dbcSqlCommentInjector.inject(sql, "orders", "postgresql", "h", "shop"));
  }
}

/** Verifies {@link R2dbcSqlCommentInjector#inject} embeds the base hash in dynamic_service mode. */
@WithConfig(key = "dbm.propagation.mode", value = "dynamic_service")
class R2dbcSqlCommentInjectorDynamicServiceForkedTest extends AbstractInstrumentationTest {

  @Test
  void dynamicServiceInjectsBaseHashInComment() {
    BaseHash.updateBaseHash(123456789L);

    String injected = R2dbcSqlCommentInjector.inject("SELECT 1", "orders", "h2", "h", "shop");

    assertTrue(injected.contains("ddsh='123456789'"), "missing base hash: " + injected);
  }
}
