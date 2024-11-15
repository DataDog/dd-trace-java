package datadog.trace.api.iast.securitycontrol;

import datadog.trace.api.iast.VulnerabilityMarks;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

public class SecurityControlFormatter {

  private static final Logger log = org.slf4j.LoggerFactory.getLogger(SecurityControlFormatter.class);

  private static final String SECURITY_CONTROL_DELIMITER = ";";
  private static final String SECURITY_CONTROL_FIELD_DELIMITER = ":";
  private static final String SECURITY_CONTROL_ELEMENT_DELIMITER = "," ;

  private static final String ALL = "*";


  @Nullable
  public static List<SecurityControl> format (final @Nonnull String securityControlString) {

    if (securityControlString.isEmpty()) {
      log.warn("Security control configuration is empty");
      return null;
    }

    String config = securityControlString.replaceAll("\\s+", "");

    String[] list = config.split(SECURITY_CONTROL_DELIMITER);

    List<SecurityControl> securityControls = new ArrayList<>(list.length);

    for (String s : list) {
      SecurityControl securityControl = getSecurityControl(s);
      if (securityControl != null) {
        securityControls.add(securityControl);
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
    String className = split[2];
    String method = split[3];

    String[] parameterTypes = null;
    int[] parametersToMark = null;

    if (split.length > 4) {
      String[] elements = split[4].split(SECURITY_CONTROL_ELEMENT_DELIMITER);
      if (elements.length > 0) {
        if (isNumeric(elements[0])) {
          parametersToMark = getParametersToMark(elements);
        }else {
          parameterTypes = elements;
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
    int marks = NOT_MARKED;
    for (String element : elements) {
      marks |= VulnerabilityMarks.getMarkFromVulnerabitityType(element);
    }
    return marks;
  }

  private static int[] getParametersToMark(String[] elements) {
    return Arrays.stream(elements)
        .mapToInt(Integer::parseInt)
        .toArray();
  }

  private static boolean isNumeric(String str) {
    return str.matches("[0-9]+");
  }



}
