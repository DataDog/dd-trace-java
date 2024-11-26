package datadog.trace.api.iast.securitycontrol;

import static datadog.trace.api.iast.VulnerabilityMarks.CUSTOM_SECURITY_CONTROL_MARK;

import datadog.trace.api.iast.VulnerabilityMarks;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;

@SuppressForbidden // Suppresses the warning for using split method
public class SecurityControlFormatter {

  private static final Logger log =
      org.slf4j.LoggerFactory.getLogger(SecurityControlFormatter.class);

  private static final String SECURITY_CONTROL_DELIMITER = ";";
  private static final String SECURITY_CONTROL_FIELD_DELIMITER = ":";
  private static final String SECURITY_CONTROL_ELEMENT_DELIMITER = ",";

  private static final String ALL = "*";

  @Nullable
  public static List<SecurityControl> format(final @Nonnull String securityControlString) {

    if (securityControlString.isEmpty()) {
      log.warn("Security control configuration is empty");
      return null;
    }

    String config = securityControlString.replaceAll("\\s+", "");

    String[] list = config.split(SECURITY_CONTROL_DELIMITER);

    List<SecurityControl> securityControls = new ArrayList<>(list.length);

    for (String s : list) {
      try {
        SecurityControl securityControl = getSecurityControl(s);
        if (securityControl != null) {
          securityControls.add(securityControl);
        }
      } catch (Exception e) {
        log.warn("Security control configuration is invalid: {}", s);
      }
    }
    return securityControls.isEmpty() ? null : securityControls;
  }

  private static SecurityControl getSecurityControl(@Nonnull final String config) {
    if (config.isEmpty()) {
      log.warn("Security control configuration is empty");
      return null;
    }
    String[] split = config.split(SECURITY_CONTROL_FIELD_DELIMITER);
    if (split.length < 4) {
      log.warn("Security control configuration is invalid: {}", config);
      return null;
    }
    SecurityControlType type = SecurityControlType.valueOf(split[0]);
    if (type == null) {
      log.warn("Security control type is invalid: {}", split[0]);
      return null;
    }
    int marks = getMarks(split[1]);
    String className = split[2].replaceAll("\\.", "/");
    String method = split[3];

    List<String> parameterTypes = null;
    Set<Integer> parametersToMark = null;

    if (split.length > 4) {
      String[] elements = split[4].split(SECURITY_CONTROL_ELEMENT_DELIMITER);
      if (elements.length > 0) {
        if (isNumeric(elements[0])) {
          if (split.length != 5) {
            log.warn("Security control configuration is invalid: {}", config);
            return null;
          }
          parametersToMark = getParametersToMark(elements);
        } else {
          parameterTypes = Arrays.asList(elements);
        }
      }
    }
    if (split.length > 5) {
      String[] elements = split[5].split(SECURITY_CONTROL_ELEMENT_DELIMITER);
      parametersToMark = getParametersToMark(elements);
    }

    return new SecurityControl(type, marks, className, method, parameterTypes, parametersToMark);
  }

  private static int getMarks(String s) {
    if (s.equals(ALL)) {
      return VulnerabilityMarks.markForAll();
    }
    String[] elements = s.split(SECURITY_CONTROL_ELEMENT_DELIMITER);
    int marks = CUSTOM_SECURITY_CONTROL_MARK;
    for (String element : elements) {
      marks |= VulnerabilityMarks.getMarkFromVulnerabitityType(element);
    }
    return marks;
  }

  private static Set<Integer> getParametersToMark(String[] elements) {
    return Arrays.stream(elements)
        .map(Integer::parseInt)
        .collect(java.util.stream.Collectors.toSet());
  }

  private static boolean isNumeric(String str) {
    return str.matches("[0-9]+");
  }
}
