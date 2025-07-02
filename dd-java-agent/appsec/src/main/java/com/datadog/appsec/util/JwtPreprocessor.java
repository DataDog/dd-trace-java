package com.datadog.appsec.util;

import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.MapDataBundle;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class JwtPreprocessor {
  private static final Moshi MOSHI = new Moshi.Builder().build();
  private static final JsonAdapter<Map<String, Object>> MAP_ADAPTER =
      MOSHI.adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

  private static final String HEADER_KEY = "header";
  private static final String PAYLOAD_KEY = "payload";
  private static final String SIGNATURE_KEY = "signature";
  private static final String AVAILABLE_KEY = "available";

  public static DataBundle processJwt(String jwtToken) {
    if (jwtToken == null || jwtToken.trim().isEmpty()) {
      return null;
    }

    // Basic JWT format check
    if (!jwtToken.contains(".")) {
      return null;
    }

    try {
      String[] parts = jwtToken.split("\\.");
      if (parts.length < 2 || parts.length > 3) {
        return null;
      }

      // Decode header using Base64URL decoder
      String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
      Map<String, Object> header = MAP_ADAPTER.fromJson(headerJson);
      if (header == null) {
        return null;
      }

      // Decode payload using Base64URL decoder
      String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
      Map<String, Object> payload = MAP_ADAPTER.fromJson(payloadJson);
      if (payload == null) {
        return null;
      }

      // Convert string numbers to actual numbers for standard JWT claims
      convertNumericClaims(payload);

      // Create signature information
      Map<String, Object> signature = new HashMap<>();
      signature.put(AVAILABLE_KEY, parts.length == 3);

      // Create JWT structure according to technical specification
      Map<String, Object> jwt = new HashMap<>();
      jwt.put(HEADER_KEY, header);
      jwt.put(PAYLOAD_KEY, payload);
      jwt.put(SIGNATURE_KEY, signature);

      return MapDataBundle.of(KnownAddresses.REQUEST_JWT, jwt);
    } catch (Exception e) {
      return null;
    }
  }

  private static void convertNumericClaims(Map<String, Object> payload) {
    // Convert common numeric JWT claims from string or double to long
    String[] numericClaims = {"exp", "iat", "nbf"};
    for (String claim : numericClaims) {
      Object value = payload.get(claim);
      if (value instanceof String) {
        try {
          long longValue = Long.parseLong((String) value);
          payload.put(claim, longValue);
        } catch (NumberFormatException ignored) {
          // Keep as string if parsing fails
        }
      } else if (value instanceof Double) {
        // Moshi parses numbers as Double, convert to Long for timestamp claims
        long longValue = ((Double) value).longValue();
        payload.put(claim, longValue);
      }
    }
  }
}
