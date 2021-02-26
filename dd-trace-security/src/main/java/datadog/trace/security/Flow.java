package datadog.trace.security;

import java.util.Map;

public interface Flow {
    void recordAttack(EngineRule rule, Map<String, Object> infos);

    void block(EngineRule rule, Map<String, Object> infos);

    boolean hasException();

    Exception getException();

    boolean shouldInterrupt();

    void mergeFrom(Flow rhs);
}
