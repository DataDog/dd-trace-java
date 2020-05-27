package com.datadog.profiling.mlt;

import datadog.trace.profiling.Session;
import datadog.trace.profiling.SessionFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JMXSessionFactory implements SessionFactory {

  private final Map<Long, JMXSession> jmxSessions = new ConcurrentHashMap<>();
  private final JMXSampler sampler;

  public JMXSessionFactory(StackTraceSink sink) {
    this.sampler = new JMXSampler(sink);
  }

  @Override
  public Session createSession(String id, Thread thread) {
    long threadId = thread.getId();
    JMXSession session =
        jmxSessions.computeIfAbsent(threadId, key -> createNewSession(id, threadId));
    session.activate();
    return session;
  }

  @Override
  public void shutdown() {
    sampler.shutdown();
  }

  // This method is invoked under the assumption that we are using a ConcurrentHashMap
  // and the method Map#computeIfAbsent is therefore atomic for the update of the value
  // inside the map
  private JMXSession createNewSession(String id, long threadId) {
    sampler.addThreadId(threadId);
    return new JMXSession(id, threadId, this::cleanup);
  }

  private void cleanup(JMXSession session) {
    jmxSessions.computeIfPresent(session.getThreadId(), this::closeSession);
  }

  // This method is invoked under the assumption that we are using a ConcurrentHashMap
  // and the method Map#computeIfPresent is therefore atomic for the update of the value
  // inside the map
  private JMXSession closeSession(Long key, JMXSession jmxSession) {
    int current = jmxSession.deactivate();
    if (current == 0) {
      sampler.removeThread(jmxSession.getThreadId());
      return null;
    }
    return jmxSession;
  }
}
