package datadog.appsec.api.user;

import java.util.Map;

public interface UserService {

  UserService NO_OP =
      new UserService() {
        @Override
        public void trackUserEvent(
            final String userId, final Map<String, String> metadata, final boolean propagated) {}
      };

  void trackUserEvent(String userId, Map<String, String> metadata, boolean propagated);
}
