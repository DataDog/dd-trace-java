package datadog.trace.civisibility.ci.env;

import java.util.Collections;
import java.util.Map;

public interface CiEnvironment {

  CiEnvironment NO_OP =
      new CiEnvironment() {

        @Override
        public String get(String name) {
          return null;
        }

        @Override
        public Map<String, String> get() {
          return Collections.emptyMap();
        }
      };

  String get(String name);

  Map<String, String> get();
}
