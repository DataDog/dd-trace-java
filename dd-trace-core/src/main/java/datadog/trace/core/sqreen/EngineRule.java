package datadog.trace.core.sqreen;

import java.util.Map;

public interface EngineRule {
    String getName();
    Map<String, Object> getData();
    boolean isBlock();
}
