package datadog.trace.civisibility.ci.env;

import java.util.Map;

public interface CiEnvironment {

  String get(String name);

  Map<String, String> get();
}
