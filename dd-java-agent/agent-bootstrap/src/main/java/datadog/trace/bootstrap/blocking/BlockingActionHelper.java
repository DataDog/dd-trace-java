package datadog.trace.bootstrap.blocking;

import static datadog.trace.api.config.AppSecConfig.APPSEC_HTTP_BLOCKED_TEMPLATE_HTML;
import static datadog.trace.api.config.AppSecConfig.APPSEC_HTTP_BLOCKED_TEMPLATE_JSON;
import static java.lang.ClassLoader.getSystemClassLoader;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.Config;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlockingActionHelper {
  private static final Logger log = LoggerFactory.getLogger(BlockingActionHelper.class);

  private static final int DEFAULT_HTTP_CODE = 403;
  private static final int MAX_ALLOWED_TEMPLATE_SIZE = 1024 * 500; // 500 kiB

  private static volatile byte[] TEMPLATE_HTML;
  private static volatile byte[] TEMPLATE_JSON;

  public static final String CONTENT_TYPE_HTML = "text/html;charset=utf-8";
  public static final String CONTENT_TYPE_JSON = "application/json";

  static {
    reset(Config.get());
  }

  public enum TemplateType {
    JSON,
    HTML;
  }

  public static int getHttpCode(int actionHttpCode) {
    if (actionHttpCode < 200 || actionHttpCode > 599) {
      return DEFAULT_HTTP_CODE;
    }
    return actionHttpCode;
  }

  enum Specificity {
    UNSPECIFIED,
    ASTERISK,
    PARTIAL,
    FULL;

    public boolean isMoreSpecificThan(Specificity other) {
      return ordinal() > other.ordinal();
    }
  }

  public static TemplateType determineTemplateType(
      BlockingContentType blockingContentType, String acceptHeader) {
    if (blockingContentType == BlockingContentType.HTML) {
      return TemplateType.HTML;
    }
    if (blockingContentType == BlockingContentType.JSON) {
      return TemplateType.JSON;
    }
    if (blockingContentType == BlockingContentType.NONE) {
      throw new IllegalArgumentException("Does not accept BlockingContentType.NONE");
    }

    float jsonPref = 0;
    Specificity curJsonSpecificity = Specificity.UNSPECIFIED;
    float htmlPref = 0;
    Specificity curHtmlSpecificity = Specificity.UNSPECIFIED;

    if (acceptHeader == null) {
      // then everything is accepted. We prefer json
      jsonPref = 1.0f;
    } else {
      int[] pos = new int[] {0};
      float[] quality = new float[] {0f};

      String mediaType;
      while ((mediaType = nextMediaRange(acceptHeader, pos, quality)) != null) {
        if (mediaType.equals("*/*")) {
          if (Specificity.ASTERISK.isMoreSpecificThan(curJsonSpecificity)) {
            jsonPref = quality[0];
            curJsonSpecificity = Specificity.ASTERISK;
          }
          if (Specificity.ASTERISK.isMoreSpecificThan(curHtmlSpecificity)) {
            htmlPref = quality[0];
            curHtmlSpecificity = Specificity.ASTERISK;
          }
        } else if (mediaType.equals("text/*")) {
          if (Specificity.PARTIAL.isMoreSpecificThan(curHtmlSpecificity)) {
            htmlPref = quality[0];
            curHtmlSpecificity = Specificity.PARTIAL;
          }
        } else if (mediaType.equals("text/html")) {
          htmlPref = quality[0];
          curHtmlSpecificity = Specificity.FULL;
        } else if (mediaType.equals("application/*")) {
          if (Specificity.PARTIAL.isMoreSpecificThan(curJsonSpecificity)) {
            jsonPref = quality[0];
            curJsonSpecificity = Specificity.PARTIAL;
          }
        } else if (mediaType.equals("application/json")) {
          jsonPref = quality[0];
          curJsonSpecificity = Specificity.FULL;
        }
      }
    }

    if (htmlPref > jsonPref) {
      return TemplateType.HTML;
    }
    return TemplateType.JSON;
  }

  public static byte[] getTemplate(TemplateType type) {
    return getTemplate(type, null);
  }

  public static byte[] getTemplate(TemplateType type, String securityResponseId) {
    byte[] template;
    if (type == TemplateType.JSON) {
      template = TEMPLATE_JSON;
    } else if (type == TemplateType.HTML) {
      template = TEMPLATE_HTML;
    } else {
      return null;
    }

    // Use "NOT AVAILABLE" when securityResponseId is not present
    String replacementValue = (securityResponseId == null || securityResponseId.isEmpty())
        ? "NOT AVAILABLE"
        : securityResponseId;

    String templateString = new String(template, java.nio.charset.StandardCharsets.UTF_8);
    String replacedTemplate = templateString.replace("[security_response_id]", replacementValue);
    return replacedTemplate.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  public static String getContentType(TemplateType type) {
    if (type == TemplateType.JSON) {
      return CONTENT_TYPE_JSON;
    } else if (type == TemplateType.HTML) {
      return CONTENT_TYPE_HTML;
    }
    return null;
  }

  private static final Pattern MEDIA_TYPE_PATTERN =
      Pattern.compile(
          "(?x)^[\\ \\t]* ( [!\\#$%&'*+\\-.^_`|~\\da-zA-Z]+/[!\\#$%&'*+\\-.^_`|~\\da-zA-Z]+ )");

  private static final Pattern QUALITY_PATTERN =
      Pattern.compile(
          "(?x);[\\ \\t]* q=( (?:0(?:\\.\\d{0,3})?) | (?:1(?:\\.0{0,3})?) )  (?:$|,|;|[\\ \\t])");

  private static String nextMediaRange(String s, int[] pos, float[] quality) {
    int initPos = pos[0];

    if (initPos >= s.length()) {
      return null;
    }

    int endCommaSep = s.indexOf(',', initPos);
    if (endCommaSep == -1) {
      endCommaSep = s.length();
    }
    pos[0] = endCommaSep + 1; // pos for next iter

    String commaSepToken = s.substring(initPos, endCommaSep);
    Matcher mediaRangeMatcher = MEDIA_TYPE_PATTERN.matcher(commaSepToken);
    if (!mediaRangeMatcher.find()) {
      return null; // error / trailing comma
    }

    Matcher qualityMatcher = QUALITY_PATTERN.matcher(commaSepToken);
    if (qualityMatcher.find()) {
      quality[0] = Float.parseFloat(qualityMatcher.group(1));
    } else {
      quality[0] = 1.0f;
    }

    return mediaRangeMatcher.group(1);
  }

  // public for testing
  public static void reset(Config config) {
    TEMPLATE_HTML = null;
    TEMPLATE_JSON = null;

    String appSecHttpBlockedTemplateHtml = config.getAppSecHttpBlockedTemplateHtml();
    if (appSecHttpBlockedTemplateHtml != null) {
      File f = new File(appSecHttpBlockedTemplateHtml);
      if (!f.isFile()) {
        log.warn(
            "File referenced in config option {} does not exist: {}",
            APPSEC_HTTP_BLOCKED_TEMPLATE_HTML,
            f);
      } else {
        TEMPLATE_HTML = readIntoByteArray(f);
      }
    }
    if (TEMPLATE_HTML == null) {
      TEMPLATE_HTML = readDefaultTemplate("html");
    }

    String appSecHttpBlockedTemplateJson = config.getAppSecHttpBlockedTemplateJson();
    if (appSecHttpBlockedTemplateJson != null) {
      File f = new File(appSecHttpBlockedTemplateJson);
      if (!f.isFile()) {
        log.warn(
            "File referenced in config option {} does not exist: {}",
            APPSEC_HTTP_BLOCKED_TEMPLATE_JSON,
            f);
      } else {
        TEMPLATE_JSON = readIntoByteArray(f);
      }
    }
    if (TEMPLATE_JSON == null) {
      TEMPLATE_JSON = readDefaultTemplate("json");
    }
  }

  private static byte[] readDefaultTemplate(String ext) {
    try (InputStream is =
        getSystemClassLoader()
            .getResourceAsStream("datadog/trace/bootstrap/blocking/template." + ext)) {
      if (is == null) {
        log.error("Could not open default {} template", ext);
        return new byte[] {'e', 'r', 'r', 'o', 'r'};
      }

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int read;
      while ((read = is.read(b)) != -1) {
        baos.write(b, 0, read);
      }
      return baos.toByteArray();
    } catch (IOException e) {
      log.error("Could not read default {} template", ext, e);
      return new byte[] {'e', 'r', 'r', 'o', 'r'};
    }
  }

  private static byte[] readIntoByteArray(File f) {
    long fileSize = f.length();
    if (fileSize > MAX_ALLOWED_TEMPLATE_SIZE) {
      log.warn(
          "Template file {} cannot be larger than {} bytes, got file {} bytes long",
          f,
          MAX_ALLOWED_TEMPLATE_SIZE,
          fileSize);
      return null;
    }
    byte[] res = new byte[(int) fileSize];
    try (FileInputStream fis = new FileInputStream(f)) {
      int read = fis.read(res);
      if (read != fileSize || fis.read() != -1) {
        log.warn("Template file {} changed before it could be fully read", f);
        return null;
      }
    } catch (FileNotFoundException e) {
      log.warn("Template file {} could not be opened", f);
      return null;
    } catch (IOException e) {
      log.warn("Error reading template file {}", f, e);
      return null;
    }
    return res;
  }
}
