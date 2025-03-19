package datadog.trace.civisibility;

/** Constants that are not part of the internal (or external) API. */
public interface Constants {

  /**
   * Indicates that early flakiness detection feature was aborted in a test session because too many
   * test cases were considered new.
   */
  String EFD_ABORT_REASON_FAULTY = "faulty";

  String CI_VISIBILITY_INSTRUMENTATION_NAME = "civisibility";

  /**
   * Env var containing SHA of the feature branch HEAD commit when running in a PR. Set manually if
   * the necessary data is not exposed by the CI provider
   */
  String DDCI_PULL_REQUEST_SOURCE_SHA = "DDCI_PULL_REQUEST_SOURCE_SHA";

  /**
   * Env var containing SHA of the target branch HEAD commit when running in a PR. Set manually if
   * the necessary data is not exposed by the CI provider
   */
  String DDCI_PULL_REQUEST_TARGET_SHA = "DDCI_PULL_REQUEST_TARGET_SHA";
}
