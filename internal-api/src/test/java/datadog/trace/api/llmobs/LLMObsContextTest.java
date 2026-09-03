package datadog.trace.api.llmobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import org.junit.jupiter.api.Test;

class LLMObsContextTest {
  @Test
  void rootSpanIdIsUndefined() {
    assertEquals("undefined", LLMObsContext.ROOT_SPAN_ID);
  }

  @Test
  void currentReturnsNullWhenNoContextAttached() {
    assertNull(LLMObsContext.current());
  }

  @Test
  void currentSessionIdReturnsNullWhenNoContextAttached() {
    assertNull(LLMObsContext.currentSessionId());
  }

  @Test
  void attachStoresSpanContext() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx)) {
      assertEquals(ctx, LLMObsContext.current());
    }
    assertNull(LLMObsContext.current());
  }

  @Test
  void attachWithoutSessionIdLeavesSessionIdNull() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx)) {
      assertNull(LLMObsContext.currentSessionId());
    }
  }

  @Test
  void attachWithSessionIdStoresBothContextAndSessionId() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx, "session-123")) {
      assertEquals(ctx, LLMObsContext.current());
      assertEquals("session-123", LLMObsContext.currentSessionId());
    }
    assertNull(LLMObsContext.current());
    assertNull(LLMObsContext.currentSessionId());
  }

  @Test
  void attachWithNullSessionIdIgnoresSessionId() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx, null)) {
      assertEquals(ctx, LLMObsContext.current());
      assertNull(LLMObsContext.currentSessionId());
    }
  }

  @Test
  void attachWithEmptySessionIdIgnoresSessionId() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx, "")) {
      assertEquals(ctx, LLMObsContext.current());
      assertNull(LLMObsContext.currentSessionId());
    }
  }

  @Test
  void nestedScopesRestoreParentContextOnClose() {
    AgentSpanContext outer = mock(AgentSpanContext.class);
    AgentSpanContext inner = mock(AgentSpanContext.class);
    try (ContextScope outerScope = LLMObsContext.attach(outer, "outer-session")) {
      assertEquals(outer, LLMObsContext.current());
      assertEquals("outer-session", LLMObsContext.currentSessionId());
      try (ContextScope innerScope = LLMObsContext.attach(inner, "inner-session")) {
        assertEquals(inner, LLMObsContext.current());
        assertEquals("inner-session", LLMObsContext.currentSessionId());
      }
      assertEquals(outer, LLMObsContext.current());
      assertEquals("outer-session", LLMObsContext.currentSessionId());
    }
    assertNull(LLMObsContext.current());
    assertNull(LLMObsContext.currentSessionId());
  }

  @Test
  void childScopeInheritsParentSessionId() {
    AgentSpanContext parent = mock(AgentSpanContext.class);
    AgentSpanContext child = mock(AgentSpanContext.class);
    try (ContextScope parentScope = LLMObsContext.attach(parent, "inherited-session")) {
      try (ContextScope childScope = LLMObsContext.attach(child)) {
        assertEquals(child, LLMObsContext.current());
        assertEquals("inherited-session", LLMObsContext.currentSessionId());
      }
    }
  }

  @Test
  void currentAgentVersionReturnsNullWhenNoContextAttached() {
    assertNull(LLMObsContext.currentAgentVersion());
  }

  @Test
  void attachWithoutAgentVersionLeavesAgentVersionNull() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx, null, null)) {
      assertNull(LLMObsContext.currentAgentVersion());
    }
  }

  @Test
  void attachWithAgentVersionStoresIt() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx, null, "v3")) {
      assertEquals(ctx, LLMObsContext.current());
      assertEquals("v3", LLMObsContext.currentAgentVersion());
    }
    assertNull(LLMObsContext.currentAgentVersion());
  }

  @Test
  void attachWithEmptyAgentVersionIgnoresAgentVersion() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx, null, "")) {
      assertNull(LLMObsContext.currentAgentVersion());
    }
  }

  @Test
  void nestedScopesRestoreParentAgentVersionOnClose() {
    AgentSpanContext outer = mock(AgentSpanContext.class);
    AgentSpanContext inner = mock(AgentSpanContext.class);
    try (ContextScope outerScope = LLMObsContext.attach(outer, null, "v1")) {
      assertEquals("v1", LLMObsContext.currentAgentVersion());
      try (ContextScope innerScope = LLMObsContext.attach(inner, null, "v2")) {
        assertEquals("v2", LLMObsContext.currentAgentVersion());
      }
      assertEquals("v1", LLMObsContext.currentAgentVersion());
    }
    assertNull(LLMObsContext.currentAgentVersion());
  }

  @Test
  void childScopeInheritsParentAgentVersion() {
    AgentSpanContext parent = mock(AgentSpanContext.class);
    AgentSpanContext child = mock(AgentSpanContext.class);
    try (ContextScope parentScope = LLMObsContext.attach(parent, null, "v3")) {
      try (ContextScope childScope = LLMObsContext.attach(child)) {
        assertEquals(child, LLMObsContext.current());
        assertEquals("v3", LLMObsContext.currentAgentVersion());
      }
    }
  }

  // ── 5-arg attach (pagent attribution) ────────────────────────────────────

  @Test
  void currentParentAgentSpanIdReturnsNullWhenNoContextAttached() {
    assertNull(LLMObsContext.currentParentAgentSpanId());
  }

  @Test
  void currentParentAgentNameReturnsNullWhenNoContextAttached() {
    assertNull(LLMObsContext.currentParentAgentName());
  }

  @Test
  void fiveArgAttachStoresAllFields() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx, "session-1", "v2", "span-99", "my-agent")) {
      assertEquals(ctx, LLMObsContext.current());
      assertEquals("session-1", LLMObsContext.currentSessionId());
      assertEquals("v2", LLMObsContext.currentAgentVersion());
      assertEquals("span-99", LLMObsContext.currentParentAgentSpanId());
      assertEquals("my-agent", LLMObsContext.currentParentAgentName());
    }
    assertNull(LLMObsContext.current());
    assertNull(LLMObsContext.currentSessionId());
    assertNull(LLMObsContext.currentAgentVersion());
    assertNull(LLMObsContext.currentParentAgentSpanId());
    assertNull(LLMObsContext.currentParentAgentName());
  }

  @Test
  void fiveArgAttachWithNullSessionIdIgnoresSessionId() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx, null, null, null, null)) {
      assertNull(LLMObsContext.currentSessionId());
      assertNull(LLMObsContext.currentAgentVersion());
      assertNull(LLMObsContext.currentParentAgentSpanId());
      assertNull(LLMObsContext.currentParentAgentName());
    }
  }

  @Test
  void fiveArgAttachWithEmptySessionIdIgnoresSessionId() {
    AgentSpanContext ctx = mock(AgentSpanContext.class);
    try (ContextScope scope = LLMObsContext.attach(ctx, "", "", null, null)) {
      assertNull(LLMObsContext.currentSessionId());
      assertNull(LLMObsContext.currentAgentVersion());
    }
  }

  @Test
  void fiveArgAttachNullPagentClearsStaleValuesFromOuterScope() {
    // When an inner (non-agent) span attaches with null pagent keys, the outer agent's
    // pagent ID and name must not leak through to that span's descendants.
    AgentSpanContext outer = mock(AgentSpanContext.class);
    AgentSpanContext inner = mock(AgentSpanContext.class);
    try (ContextScope outerScope =
        LLMObsContext.attach(outer, "s", "v1", "agent-span-id", "outer-agent")) {
      assertEquals("agent-span-id", LLMObsContext.currentParentAgentSpanId());
      assertEquals("outer-agent", LLMObsContext.currentParentAgentName());

      try (ContextScope innerScope = LLMObsContext.attach(inner, null, null, null, null)) {
        assertNull(LLMObsContext.currentParentAgentSpanId());
        assertNull(LLMObsContext.currentParentAgentName());
      }

      // Outer values are restored after inner scope closes.
      assertEquals("agent-span-id", LLMObsContext.currentParentAgentSpanId());
      assertEquals("outer-agent", LLMObsContext.currentParentAgentName());
    }
  }

  @Test
  void fiveArgAttachInnerAgentOverridesOuterAgentForDescendants() {
    AgentSpanContext outer = mock(AgentSpanContext.class);
    AgentSpanContext inner = mock(AgentSpanContext.class);
    try (ContextScope outerScope =
        LLMObsContext.attach(outer, null, null, "outer-span-id", "outer-agent")) {
      try (ContextScope innerScope =
          LLMObsContext.attach(inner, null, null, "inner-span-id", "inner-agent")) {
        assertEquals("inner-span-id", LLMObsContext.currentParentAgentSpanId());
        assertEquals("inner-agent", LLMObsContext.currentParentAgentName());
      }
      assertEquals("outer-span-id", LLMObsContext.currentParentAgentSpanId());
      assertEquals("outer-agent", LLMObsContext.currentParentAgentName());
    }
  }

  @Test
  void fiveArgAttachNullPagentNameClearsNameButNotSpanId() {
    // An agent with a null name (e.g. manifest not yet set) must not let outer agent's name
    // leak into its scope — only the span ID is set.
    AgentSpanContext outer = mock(AgentSpanContext.class);
    AgentSpanContext inner = mock(AgentSpanContext.class);
    try (ContextScope outerScope =
        LLMObsContext.attach(outer, null, null, "outer-span-id", "outer-agent")) {
      try (ContextScope innerScope =
          LLMObsContext.attach(inner, null, null, "inner-span-id", null)) {
        assertEquals("inner-span-id", LLMObsContext.currentParentAgentSpanId());
        assertNull(LLMObsContext.currentParentAgentName());
      }
    }
  }
}
