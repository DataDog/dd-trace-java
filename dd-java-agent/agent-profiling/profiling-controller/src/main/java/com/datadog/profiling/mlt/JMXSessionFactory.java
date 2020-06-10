package com.datadog.profiling.mlt;

import datadog.trace.mlt.Session;
import datadog.trace.mlt.SessionFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JMXSessionFactory implements SessionFactory {
  private final Map<Long, JMXSession> jmxSessions = new ConcurrentHashMap<>();
  private final ThreadScopeMapper threadScopeMapper = new ThreadScopeMapper();
  private final JMXSampler sampler;

  public JMXSessionFactory() {
    this.sampler = new JMXSampler(threadScopeMapper);
  }

  @Override
  public Session createSession(String id, Thread thread) {
    ScopeManager scopeManager = threadScopeMapper.forThread(thread);
    ScopeStackCollector scopeStackCollector = scopeManager.startScope(id);
    long threadId = thread.getId();
    // need to use ConcurrentHashMap#compute method to insure atomicity of createNewSession call
    return jmxSessions.compute(
        threadId, (key, jmxSession) -> createNewSession(id, threadId, scopeStackCollector));
  }

  @Override
  public void shutdown() {
    sampler.shutdown();
  }

  // This method is invoked under the assumption that we are using a ConcurrentHashMap
  // and the method Map#computeIfAbsent is therefore atomic for the update of the value
  // inside the map
  private JMXSession createNewSession(
      String id, long threadId, ScopeStackCollector scopeStackCollector) {
    sampler.addThreadId(threadId);
    return new JMXSession(id, threadId, scopeStackCollector, this::cleanup);
  }

  private void cleanup(JMXSession session) {
    jmxSessions.computeIfPresent(session.getThreadId(), this::closeSession);
  }

  // This method is invoked under the assumption that we are using a ConcurrentHashMap
  // and the method Map#computeIfPresent is therefore atomic for the update of the value
  // inside the map
  private JMXSession closeSession(Long key, JMXSession jmxSession) {
    sampler.removeThread(jmxSession.getThreadId());
    return null;
  }
}
