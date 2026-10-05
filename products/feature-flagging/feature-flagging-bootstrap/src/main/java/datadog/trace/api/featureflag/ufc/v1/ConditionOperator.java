package datadog.trace.api.featureflag.ufc.v1;

public enum ConditionOperator {
  LT,
  LTE,
  GT,
  GTE,
  MATCHES,
  NOT_MATCHES,
  ONE_OF,
  NOT_ONE_OF,
  IS_NULL,
  SEMVER_EQ,
  SEMVER_NEQ,
  SEMVER_LT,
  SEMVER_LTE,
  SEMVER_GT,
  SEMVER_GTE
}
