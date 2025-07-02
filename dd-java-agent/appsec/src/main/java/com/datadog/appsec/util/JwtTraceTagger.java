package com.datadog.appsec.util;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.IOException;
import java.util.Map;

/**
 * Utility for parsing JWT JSON from WAF derivatives and tagging selected claims on the current
 * trace/span.
 *
 * <p>Privacy: Only non-sensitive, non-user-identifying claims are tagged. Do not add claims such as
 * email, name, roles, etc.
 *
 * <p>Extensibility: Add new claims to the tagging logic as needed, ensuring privacy review.
 */
public final class JwtTraceTagger {

  private static final String JWT_DERIVATIVE_KEY = "server.request.jwt";

  private static final JsonAdapter<Map<String, Object>> JWT_JSON_ADAPTER =
      new Moshi.Builder()
          .build()
          .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

  private JwtTraceTagger() {}

  /**
   * Parses JWT JSON string from WAF derivatives and tags selected claims on the current active
   * span.
   *
   * @param derivatives Map of derivatives from WAF result, containing JWT JSON under
   *     "server.request.jwt" key
   */
  public static void tagJwtClaimsFromDerivatives(Map<String, String> derivatives) {
    if (derivatives == null) {
      return;
    }

    String jwtJson = derivatives.get(JWT_DERIVATIVE_KEY);
    if (jwtJson == null || jwtJson.isEmpty()) {
      return;
    }

    try {
      Map<String, Object> decodedJwt = JWT_JSON_ADAPTER.fromJson(jwtJson);
      if (decodedJwt != null) {
        tagJwtClaims(decodedJwt);
      }
    } catch (IOException e) {
      // Log parsing error but don't fail the request
      // Could add debug logging here if needed
    }
  }

  /**
   * Tags selected JWT claims on the provided span.
   *
   * @param decodedJwt Map representing the decoded JWT structure from JSON parsing. Expected keys:
   *     "header", "payload", "signature".
   * @param span The span to tag. If null, uses the current active span.
   */
  public static void tagJwtClaims(Map<String, Object> decodedJwt, AgentSpan span) {
    if (decodedJwt == null) {
      return;
    }
    if (span == null) {
      span = AgentTracer.activeSpan();
      if (span == null) {
        return;
      }
    }

    // Tag header claims
    Object headerObj = decodedJwt.get("header");
    if (headerObj instanceof Map) {
      Map<?, ?> header = (Map<?, ?>) headerObj;
      Object alg = header.get("alg");
      if (alg != null) {
        span.setTag("jwt.header.alg", alg.toString());
      }
      Object typ = header.get("typ");
      if (typ != null) {
        span.setTag("jwt.header.typ", typ.toString());
      }
    }

    // Tag payload claims
    Object payloadObj = decodedJwt.get("payload");
    if (payloadObj instanceof Map) {
      Map<?, ?> payload = (Map<?, ?>) payloadObj;
      Object sub = payload.get("sub");
      if (sub != null) {
        span.setTag("jwt.payload.sub", sub.toString());
      }
      Object exp = payload.get("exp");
      if (exp != null) {
        String expStr;
        if (exp instanceof Number) {
          expStr = String.format("%.0f", ((Number) exp).doubleValue());
        } else {
          expStr = exp.toString();
        }
        System.out.println("DEBUG: Tagging jwt.payload.exp: " + expStr);
        span.setTag("jwt.payload.exp", expStr);
      }
    }

    // Tag signature claims
    Object signatureObj = decodedJwt.get("signature");
    if (signatureObj instanceof Map) {
      Map<?, ?> signature = (Map<?, ?>) signatureObj;
      Object available = signature.get("available");
      if (available != null && Boolean.TRUE.equals(available)) {
        span.setTag("jwt.signature.available", available.toString());
      }
    }
  }

  /**
   * Tags selected JWT claims on the current active span.
   *
   * @param decodedJwt Map representing the decoded JWT structure from JSON parsing. Expected keys:
   *     "header", "payload", "signature".
   */
  public static void tagJwtClaims(Map<String, Object> decodedJwt) {
    tagJwtClaims(decodedJwt, null);
  }
}
