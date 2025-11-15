package datadog.appsec.api.login;

import java.util.Map;

public interface EventTrackerService {

  EventTrackerService NO_OP =
      new EventTrackerService() {
        @Override
        public void trackUserLoginSuccess(
            final String login, final String userId, final Map<String, String> metadata) {}

        @Override
        public void trackUserLoginFailure(
            final String login, final boolean exists, final Map<String, String> metadata) {}

        @Override
        public void trackCustomEventV2(
            final String eventName, final Map<String, String> metadata) {}
      };

  void trackUserLoginSuccess(String login, String userId, Map<String, String> metadata);

  void trackUserLoginFailure(String login, boolean exists, Map<String, String> metadata);

  void trackCustomEventV2(String eventName, Map<String, String> metadata);
}
