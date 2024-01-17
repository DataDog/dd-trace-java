package datadog.trace.api.iast.secrets;

import java.util.regex.Pattern;

public class HardcodedSecretMatcher {

  public static final int MIN_SECRET_LENGTH = 10;

  public static final HardcodedSecretMatcher ADOBE_CLIENT_SECRET =
      new HardcodedSecretMatcher(
          "adobe-client-secret",
          Pattern.compile("(?i)\\b((p8e-)(?i)[a-z0-9]{32})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher AGE_SECRET_KEY =
      new HardcodedSecretMatcher(
          "age-secret-key",
          Pattern.compile("AGE-SECRET-KEY-1[QPZRY9X8GF2TVDW0S3JN54KHCE6MUA7L]{58}"));
  public static final HardcodedSecretMatcher ALIBABA_ACCESS_KEY_ID =
      new HardcodedSecretMatcher(
          "alibaba-access-key-id",
          Pattern.compile("(?i)\\b((LTAI)(?i)[a-z0-9]{20})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher AUTHRESS_SERVICE_CLIENT_ACCESS_KEY =
      new HardcodedSecretMatcher(
          "authress-service-client-access-key",
          Pattern.compile(
              "(?i)\\b((?:sc|ext|scauth|authress)_[a-z0-9]{5,30}\\.[a-z0-9]{4,6}\\.acc[_-][a-z0-9-]{10,32}\\.[a-z0-9+/_=-]{30,120})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher AWS_ACCESS_TOKEN =
      new HardcodedSecretMatcher(
          "aws-access-token",
          Pattern.compile(
              "\\b((A3T[A-Z0-9]|AKIA|AGPA|AIDA|AROA|AIPA|ANPA|ANVA|ASIA)[A-Z0-9]{16})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher CLOJARS_API_TOKEN =
      new HardcodedSecretMatcher(
          "clojars-api-token", Pattern.compile("(?i)(CLOJARS_)[a-z0-9]{60}"));
  public static final HardcodedSecretMatcher DATABRICKS_API_TOKEN =
      new HardcodedSecretMatcher(
          "databricks-api-token",
          Pattern.compile("(?i)\\b(dapi[a-h0-9]{32})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher DIGITALOCEAN_ACCESS_TOKEN =
      new HardcodedSecretMatcher(
          "digitalocean-access-token",
          Pattern.compile("(?i)\\b(doo_v1_[a-f0-9]{64})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher DIGITALOCEAN_PAT =
      new HardcodedSecretMatcher(
          "digitalocean-pat",
          Pattern.compile("(?i)\\b(dop_v1_[a-f0-9]{64})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher DIGITALOCEAN_REFRESH_TOKEN =
      new HardcodedSecretMatcher(
          "digitalocean-refresh-token",
          Pattern.compile("(?i)\\b(dor_v1_[a-f0-9]{64})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher DOPPLER_API_TOKEN =
      new HardcodedSecretMatcher(
          "doppler-api-token", Pattern.compile("(dp\\.pt\\.)(?i)[a-z0-9]{43}"));
  public static final HardcodedSecretMatcher DUFFEL_API_TOKEN =
      new HardcodedSecretMatcher(
          "duffel-api-token", Pattern.compile("duffel_(test|live)_(?i)[a-z0-9_\\-=]{43}"));
  public static final HardcodedSecretMatcher DYNATRACE_API_TOKEN =
      new HardcodedSecretMatcher(
          "dynatrace-api-token", Pattern.compile("dt0c01\\.(?i)[a-z0-9]{24}\\.[a-z0-9]{64}"));
  public static final HardcodedSecretMatcher EASYPOST_API_TOKEN =
      new HardcodedSecretMatcher("easypost-api-token", Pattern.compile("\\bEZAK(?i)[a-z0-9]{54}"));
  public static final HardcodedSecretMatcher FLUTTERWAVE_PUBLIC_KEY =
      new HardcodedSecretMatcher(
          "flutterwave-public-key", Pattern.compile("FLWPUBK_TEST-(?i)[a-h0-9]{32}-X"));
  public static final HardcodedSecretMatcher FRAMEIO_API_TOKEN =
      new HardcodedSecretMatcher(
          "frameio-api-token", Pattern.compile("fio-u-(?i)[a-z0-9\\-_=]{64}"));
  public static final HardcodedSecretMatcher GCP_API_KEY =
      new HardcodedSecretMatcher(
          "gcp-api-key",
          Pattern.compile("(?i)\\b(AIza[0-9A-Za-z\\-_]{35})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher GITHUB_APP_TOKEN =
      new HardcodedSecretMatcher("github-app-token", Pattern.compile("(ghu|ghs)_[0-9a-zA-Z]{36}"));
  public static final HardcodedSecretMatcher GITHUB_FINE_GRAINED_PAT =
      new HardcodedSecretMatcher(
          "github-fine-grained-pat", Pattern.compile("github_pat_[0-9a-zA-Z_]{82}"));
  public static final HardcodedSecretMatcher GITHUB_OAUTH =
      new HardcodedSecretMatcher("github-oauth", Pattern.compile("gho_[0-9a-zA-Z]{36}"));
  public static final HardcodedSecretMatcher GITHUB_PAT =
      new HardcodedSecretMatcher("github-pat", Pattern.compile("ghp_[0-9a-zA-Z]{36}"));
  public static final HardcodedSecretMatcher GITLAB_PAT =
      new HardcodedSecretMatcher("gitlab-pat", Pattern.compile("glpat-[0-9a-zA-Z\\-_]{20}"));
  public static final HardcodedSecretMatcher GITLAB_PTT =
      new HardcodedSecretMatcher("gitlab-ptt", Pattern.compile("glptt-[0-9a-f]{40}"));
  public static final HardcodedSecretMatcher GITLAB_RRT =
      new HardcodedSecretMatcher("gitlab-rrt", Pattern.compile("GR1348941[0-9a-zA-Z\\-_]{20}"));
  public static final HardcodedSecretMatcher GRAFANA_API_KEY =
      new HardcodedSecretMatcher(
          "grafana-api-key",
          Pattern.compile(
              "(?i)\\b(eyJrIjoi[A-Za-z0-9]{70,400}={0,2})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher GRAFANA_CLOUD_API_TOKEN =
      new HardcodedSecretMatcher(
          "grafana-cloud-api-token",
          Pattern.compile(
              "(?i)\\b(glc_[A-Za-z0-9+/]{32,400}={0,2})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher GRAFANA_SERVICE_ACCOUNT_TOKEN =
      new HardcodedSecretMatcher(
          "grafana-service-account-token",
          Pattern.compile(
              "(?i)\\b(glsa_[A-Za-z0-9]{32}_[A-Fa-f0-9]{8})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher HASHICORP_TF_API_TOKEN =
      new HardcodedSecretMatcher(
          "hashicorp-tf-api-token",
          Pattern.compile("(?i)[a-z0-9]{14}\\.atlasv1\\.[a-z0-9\\-_=]{60,70}"));
  public static final HardcodedSecretMatcher JWT =
      new HardcodedSecretMatcher(
          "jwt",
          Pattern.compile(
              "\\b(ey[a-zA-Z0-9]{17,}\\.ey[a-zA-Z0-9\\/_-]{17,}\\.(?:[a-zA-Z0-9\\/_-]{10,}={0,2})?)(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher LINEAR_API_KEY =
      new HardcodedSecretMatcher("linear-api-key", Pattern.compile("lin_api_(?i)[a-z0-9]{40}"));
  public static final HardcodedSecretMatcher NPM_ACCESS_TOKEN =
      new HardcodedSecretMatcher(
          "npm-access-token",
          Pattern.compile("(?i)\\b(npm_[a-z0-9]{36})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher OPENAI_API_KEY =
      new HardcodedSecretMatcher(
          "openai-api-key",
          Pattern.compile(
              "(?i)\\b(sk-[a-zA-Z0-9]{20}T3BlbkFJ[a-zA-Z0-9]{20})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher PLANETSCALE_API_TOKEN =
      new HardcodedSecretMatcher(
          "planetscale-api-token",
          Pattern.compile(
              "(?i)\\b(pscale_tkn_(?i)[a-z0-9=\\-_\\.]{32,64})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher PLANETSCALE_OAUTH_TOKEN =
      new HardcodedSecretMatcher(
          "planetscale-oauth-token",
          Pattern.compile(
              "(?i)\\b(pscale_oauth_(?i)[a-z0-9=\\-_\\.]{32,64})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher PLANETSCALE_PASSWORD =
      new HardcodedSecretMatcher(
          "planetscale-password",
          Pattern.compile(
              "(?i)\\b(pscale_pw_(?i)[a-z0-9=\\-_\\.]{32,64})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher POSTMAN_API_TOKEN =
      new HardcodedSecretMatcher(
          "postman-api-token",
          Pattern.compile(
              "(?i)\\b(PMAK-(?i)[a-f0-9]{24}\\-[a-f0-9]{34})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher PREFECT_API_TOKEN =
      new HardcodedSecretMatcher(
          "prefect-api-token",
          Pattern.compile("(?i)\\b(pnu_[a-z0-9]{36})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher PRIVATE_KEY =
      new HardcodedSecretMatcher(
          "private-key",
          Pattern.compile(
              "(?i)-----BEGIN[ A-Z0-9_-]{0,100}PRIVATE KEY( BLOCK)?-----[\\s\\S-]*KEY( BLOCK)?----"));
  public static final HardcodedSecretMatcher PULUMI_API_TOKEN =
      new HardcodedSecretMatcher(
          "pulumi-api-token",
          Pattern.compile("(?i)\\b(pul-[a-f0-9]{40})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher PYPI_UPLOAD_TOKEN =
      new HardcodedSecretMatcher(
          "pypi-upload-token", Pattern.compile("pypi-AgEIcHlwaS5vcmc[A-Za-z0-9\\-_]{50,1000}"));
  public static final HardcodedSecretMatcher README_API_TOKEN =
      new HardcodedSecretMatcher(
          "readme-api-token",
          Pattern.compile("(?i)\\b(rdme_[a-z0-9]{70})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher RUBYGEMS_API_TOKEN =
      new HardcodedSecretMatcher(
          "rubygems-api-token",
          Pattern.compile("(?i)\\b(rubygems_[a-f0-9]{48})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher SCALINGO_API_TOKEN =
      new HardcodedSecretMatcher("scalingo-api-token", Pattern.compile("tk-us-[a-zA-Z0-9-_]{48}"));
  public static final HardcodedSecretMatcher SENDGRID_API_TOKEN =
      new HardcodedSecretMatcher(
          "sendgrid-api-token",
          Pattern.compile(
              "(?i)\\b(SG\\.(?i)[a-z0-9=_\\-\\.]{66})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher SENDINBLUE_API_TOKEN =
      new HardcodedSecretMatcher(
          "sendinblue-api-token",
          Pattern.compile(
              "(?i)\\b(xkeysib-[a-f0-9]{64}\\-(?i)[a-z0-9]{16})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher SHIPPO_API_TOKEN =
      new HardcodedSecretMatcher(
          "shippo-api-token",
          Pattern.compile(
              "(?i)\\b(shippo_(live|test)_[a-f0-9]{40})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher SHOPIFY_ACCESS_TOKEN =
      new HardcodedSecretMatcher("shopify-access-token", Pattern.compile("shpat_[a-fA-F0-9]{32}"));
  public static final HardcodedSecretMatcher SHOPIFY_CUSTOM_ACCESS_TOKEN =
      new HardcodedSecretMatcher(
          "shopify-custom-access-token", Pattern.compile("shpca_[a-fA-F0-9]{32}"));
  public static final HardcodedSecretMatcher SHOPIFY_PRIVATE_APP_ACCESS_TOKEN =
      new HardcodedSecretMatcher(
          "shopify-private-app-access-token", Pattern.compile("shppa_[a-fA-F0-9]{32}"));
  public static final HardcodedSecretMatcher SHOPIFY_SHARED_SECRET =
      new HardcodedSecretMatcher("shopify-shared-secret", Pattern.compile("shpss_[a-fA-F0-9]{32}"));
  public static final HardcodedSecretMatcher SLACK_APP_TOKEN =
      new HardcodedSecretMatcher(
          "slack-app-token", Pattern.compile("(?i)(xapp-\\d-[A-Z0-9]+-\\d+-[a-z0-9]+)"));
  public static final HardcodedSecretMatcher SLACK_BOT_TOKEN =
      new HardcodedSecretMatcher(
          "slack-bot-token", Pattern.compile("(xoxb-[0-9]{10,13}\\-[0-9]{10,13}[a-zA-Z0-9-]*)"));
  public static final HardcodedSecretMatcher SLACK_CONFIG_ACCESS_TOKEN =
      new HardcodedSecretMatcher(
          "slack-config-access-token", Pattern.compile("(?i)(xoxe.xox[bp]-\\d-[A-Z0-9]{163,166})"));
  public static final HardcodedSecretMatcher SLACK_CONFIG_REFRESH_TOKEN =
      new HardcodedSecretMatcher(
          "slack-config-refresh-token", Pattern.compile("(?i)(xoxe-\\d-[A-Z0-9]{146})"));
  public static final HardcodedSecretMatcher SLACK_LEGACY_BOT_TOKEN =
      new HardcodedSecretMatcher(
          "slack-legacy-bot-token", Pattern.compile("(xoxb-[0-9]{8,14}\\-[a-zA-Z0-9]{18,26})"));
  public static final HardcodedSecretMatcher SLACK_LEGACY_TOKEN =
      new HardcodedSecretMatcher(
          "slack-legacy-token", Pattern.compile("(xox[os]-\\d+-\\d+-\\d+-[a-fA-F\\d]+)"));
  public static final HardcodedSecretMatcher SLACK_LEGACY_WORKSPACE_TOKEN =
      new HardcodedSecretMatcher(
          "slack-legacy-workspace-token", Pattern.compile("(xox[ar]-(?:\\d-)?[0-9a-zA-Z]{8,48})"));
  public static final HardcodedSecretMatcher SLACK_USER_TOKEN =
      new HardcodedSecretMatcher(
          "slack-user-token", Pattern.compile("(xox[pe](?:-[0-9]{10,13}){3}-[a-zA-Z0-9-]{28,34})"));
  public static final HardcodedSecretMatcher SLACK_WEBHOOK_URL =
      new HardcodedSecretMatcher(
          "slack-webhook-url",
          Pattern.compile(
              "(https?:\\/\\/)?hooks.slack.com\\/(services|workflows)\\/[A-Za-z0-9+\\/]{43,46}"));
  public static final HardcodedSecretMatcher SQUARE_ACCESS_TOKEN =
      new HardcodedSecretMatcher(
          "square-access-token",
          Pattern.compile("(?i)\\b(sq0atp-[0-9A-Za-z\\-_]{22})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher SQUARE_SECRET =
      new HardcodedSecretMatcher(
          "square-secret",
          Pattern.compile("(?i)\\b(sq0csp-[0-9A-Za-z\\-_]{43})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher STRIPE_ACCESS_TOKEN =
      new HardcodedSecretMatcher(
          "stripe-access-token", Pattern.compile("(?i)(sk|pk)_(test|live)_[0-9a-z]{10,32}"));
  public static final HardcodedSecretMatcher TWILIO_API_KEY =
      new HardcodedSecretMatcher("twilio-api-key", Pattern.compile("SK[0-9a-fA-F]{32}"));
  public static final HardcodedSecretMatcher VAULT_BATCH_TOKEN =
      new HardcodedSecretMatcher(
          "vault-batch-token",
          Pattern.compile("(?i)\\b(hvb\\.[a-z0-9_-]{138,212})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));
  public static final HardcodedSecretMatcher VAULT_SERVICE_TOKEN =
      new HardcodedSecretMatcher(
          "vault-service-token",
          Pattern.compile("(?i)\\b(hvs\\.[a-z0-9_-]{90,100})(?:['|\"\"|\\n|\\r|\\s|\\x60|;]|$)"));

  public static final HardcodedSecretMatcher[] HARDCODED_SECRET_MATCHERS = {
    ADOBE_CLIENT_SECRET,
    AGE_SECRET_KEY,
    ALIBABA_ACCESS_KEY_ID,
    AUTHRESS_SERVICE_CLIENT_ACCESS_KEY,
    AWS_ACCESS_TOKEN,
    CLOJARS_API_TOKEN,
    DATABRICKS_API_TOKEN,
    DIGITALOCEAN_ACCESS_TOKEN,
    DIGITALOCEAN_PAT,
    DIGITALOCEAN_REFRESH_TOKEN,
    DOPPLER_API_TOKEN,
    DUFFEL_API_TOKEN,
    DYNATRACE_API_TOKEN,
    EASYPOST_API_TOKEN,
    FLUTTERWAVE_PUBLIC_KEY,
    FRAMEIO_API_TOKEN,
    GCP_API_KEY,
    GITHUB_APP_TOKEN,
    GITHUB_FINE_GRAINED_PAT,
    GITHUB_OAUTH,
    GITHUB_PAT,
    GITLAB_PAT,
    GITLAB_PTT,
    GITLAB_RRT,
    GRAFANA_API_KEY,
    GRAFANA_CLOUD_API_TOKEN,
    GRAFANA_SERVICE_ACCOUNT_TOKEN,
    HASHICORP_TF_API_TOKEN,
    JWT,
    LINEAR_API_KEY,
    NPM_ACCESS_TOKEN,
    OPENAI_API_KEY,
    PLANETSCALE_API_TOKEN,
    PLANETSCALE_OAUTH_TOKEN,
    PLANETSCALE_PASSWORD,
    POSTMAN_API_TOKEN,
    PREFECT_API_TOKEN,
    PRIVATE_KEY,
    PULUMI_API_TOKEN,
    PYPI_UPLOAD_TOKEN,
    README_API_TOKEN,
    RUBYGEMS_API_TOKEN,
    SCALINGO_API_TOKEN,
    SENDGRID_API_TOKEN,
    SENDINBLUE_API_TOKEN,
    SHIPPO_API_TOKEN,
    SHOPIFY_ACCESS_TOKEN,
    SHOPIFY_CUSTOM_ACCESS_TOKEN,
    SHOPIFY_PRIVATE_APP_ACCESS_TOKEN,
    SHOPIFY_SHARED_SECRET,
    SLACK_APP_TOKEN,
    SLACK_BOT_TOKEN,
    SLACK_CONFIG_ACCESS_TOKEN,
    SLACK_CONFIG_REFRESH_TOKEN,
    SLACK_LEGACY_BOT_TOKEN,
    SLACK_LEGACY_TOKEN,
    SLACK_LEGACY_WORKSPACE_TOKEN,
    SLACK_USER_TOKEN,
    SLACK_WEBHOOK_URL,
    SQUARE_ACCESS_TOKEN,
    SQUARE_SECRET,
    STRIPE_ACCESS_TOKEN,
    TWILIO_API_KEY,
    VAULT_BATCH_TOKEN,
    VAULT_SERVICE_TOKEN
  };
  private final String redactedEvidence;

  private final Pattern pattern;

  private HardcodedSecretMatcher(final String redactedEvidence, final Pattern pattern) {
    this.redactedEvidence = redactedEvidence;
    this.pattern = pattern;
  }

  public String getRedactedEvidence() {
    return redactedEvidence;
  }

  public boolean matches(final String value) {
    return pattern.matcher(value).matches();
  }
}
