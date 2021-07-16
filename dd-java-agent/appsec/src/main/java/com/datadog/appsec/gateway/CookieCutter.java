//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package com.datadog.appsec.gateway;

import com.datadog.appsec.event.data.StringKVPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/* From Jetty's CookieCutter */
public class CookieCutter {
  private CookieCutter() {}

  // should not throw
  public static List<StringKVPair> parseCookieHeader(String f) {
    if (f == null) {
      return Collections.emptyList();
    }
    f = f.trim();
    if (f.length() == 0) {
      return Collections.emptyList();
    }
    List<StringKVPair> pairs = new ArrayList<>(4);
    parseHeaderValue(pairs, f);
    return pairs;
  }

  private static void parseHeaderValue(List<StringKVPair> cookies, String hdr) {
    // Parse the header
    String name = null;
    String value = null;

    boolean invalue = false;
    boolean quoted = false;
    boolean escaped = false;
    int tokenstart = -1;
    int tokenend = -1;
    for (int i = 0, length = hdr.length(), last = length - 1; i < length; i++) {
      char c = hdr.charAt(i);

      // Handle quoted values for name or value
      if (quoted) {
        if (escaped) {
          escaped = false;
          continue;
        }

        switch (c) {
          case '"':
            tokenend = i;
            quoted = false;

            // handle quote as last character specially
            if (i == last) {
              if (invalue) value = hdr.substring(tokenstart, tokenend + 1);
              else {
                name = hdr.substring(tokenstart, tokenend + 1);
                value = "";
              }
            }
            break;

          case '\\':
            escaped = true;
            continue;
          default:
            continue;
        }
      } else {
        // Handle name and value state machines
        if (invalue) {
          // parse the value
          switch (c) {
            case ' ':
            case '\t':
              continue;

            case '"':
              if (tokenstart < 0) {
                quoted = true;
                tokenstart = i;
              }
              tokenend = i;
              if (i == last) {
                value = hdr.substring(tokenstart, tokenend + 1);
                break;
              }
              continue;

            case ';':
              if (tokenstart >= 0) value = hdr.substring(tokenstart, tokenend + 1);
              else value = "";
              tokenstart = -1;
              invalue = false;
              break;

            default:
              if (tokenstart < 0) tokenstart = i;
              tokenend = i;
              if (i == last) {
                value = hdr.substring(tokenstart, tokenend + 1);
                break;
              }
              continue;
          }
        } else {
          // parse the name
          switch (c) {
            case ' ':
            case '\t':
              continue;

            case '"':
              if (tokenstart < 0) {
                quoted = true;
                tokenstart = i;
              }
              tokenend = i;
              if (i == last) {
                name = hdr.substring(tokenstart, tokenend + 1);
                value = "";
                break;
              }
              continue;

            case ';':
              if (tokenstart >= 0) {
                name = hdr.substring(tokenstart, tokenend + 1);
                value = "";
              }
              tokenstart = -1;
              break;

            case '=':
              if (tokenstart >= 0) name = hdr.substring(tokenstart, tokenend + 1);
              tokenstart = -1;
              invalue = true;
              continue;

            default:
              if (tokenstart < 0) tokenstart = i;
              tokenend = i;
              if (i == last) {
                name = hdr.substring(tokenstart, tokenend + 1);
                value = "";
                break;
              }
              continue;
          }
        }
      }

      // If after processing the current character we have a value and a name, then it is a cookie
      if (value != null && name != null) {
        name = unquoteOnly(name);
        value = unquoteOnly(value);

        if (!name.startsWith("$")) {
          StringKVPair cookie = new StringKVPair(name, value);
          cookies.add(cookie);
        }

        name = null;
        value = null;
      }
    }
  }

  /* ------------------------------------------------------------ */

  /**
   * Unquote a string, NOT converting unicode sequences
   *
   * @param s The string to unquote.
   * @return quoted string
   */
  public static String unquoteOnly(String s) {
    if (s == null) return null;
    if (s.length() < 2) return s;

    char first = s.charAt(0);
    char last = s.charAt(s.length() - 1);
    if (first != last || (first != '"' && first != '\'')) return s;

    StringBuilder b = new StringBuilder(s.length() - 2);
    boolean escape = false;
    for (int i = 1; i < s.length() - 1; i++) {
      char c = s.charAt(i);

      if (escape) {
        escape = false;
        b.append(c);
      } else if (c == '\\') {
        escape = true;
      } else {
        b.append(c);
      }
    }

    return b.toString();
  }
}
