/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.sarto.onejar.teavm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Build-JVM reader for the versioned target indexes on TeaVM's compilation classpath. */
final class RuntimeTargetIndex {
  static final String RESOURCE = "META-INF/sarto/runtime-targets.properties";

  private final Map<String, TargetEntry> classes;
  private final Map<String, TargetEntry> packages;

  private RuntimeTargetIndex(
      Map<String, TargetEntry> classes, Map<String, TargetEntry> packages) {
    this.classes = Map.copyOf(classes);
    this.packages = Map.copyOf(packages);
  }

  static RuntimeTargetIndex load(ClassLoader preferredLoader) {
    try {
      Set<URL> resources = new LinkedHashSet<>();
      collect(resources, preferredLoader);
      collect(resources, RuntimeTargetIndex.class.getClassLoader());
      collect(resources, Thread.currentThread().getContextClassLoader());
      Map<String, TargetEntry> classes = new LinkedHashMap<>();
      Map<String, TargetEntry> packages = new LinkedHashMap<>();
      for (URL resource : resources) {
        try (InputStream input = resource.openStream()) {
          add(input, resource.toString(), classes, packages);
        }
      }
      return new RuntimeTargetIndex(classes, packages);
    } catch (IOException | RuntimeException failure) {
      throw new IllegalStateException("Could not load Sarto runtime target indexes", failure);
    }
  }

  boolean isJvm(String className) {
    TargetEntry direct = classes.get(className);
    if (direct == null) {
      int nested = className.indexOf('$');
      if (nested > 0) {
        direct = classes.get(className.substring(0, nested));
      }
    }
    if (direct != null) {
      return direct.target() == Target.JVM;
    }
    int separator = className.lastIndexOf('.');
    TargetEntry packageEntry =
        packages.get(separator < 0 ? "" : className.substring(0, separator));
    return packageEntry != null && packageEntry.target() == Target.JVM;
  }

  private static void collect(Set<URL> resources, ClassLoader loader) throws IOException {
    if (loader == null) {
      return;
    }
    Enumeration<URL> found = loader.getResources(RESOURCE);
    while (found.hasMoreElements()) {
      resources.add(found.nextElement());
    }
  }

  private static void add(
      InputStream input,
      String resource,
      Map<String, TargetEntry> classes,
      Map<String, TargetEntry> packages)
      throws IOException {
    Map<String, String> values = values(input);
    String format = required(values, "format", resource);
    if (!"1".equals(format)) {
      throw new IOException("Unsupported Sarto runtime target index format " + format + " in " + resource);
    }
    String origin = decoded(required(values, "origin", resource));
    int count = integer(values, "entries", resource);
    for (int i = 0; i < count; i++) {
      String prefix = "entry." + i + ".";
      String kind = required(values, prefix + "kind", resource);
      String name = decoded(required(values, prefix + "name", resource));
      Target target;
      try {
        target = Target.valueOf(required(values, prefix + "target", resource));
      } catch (IllegalArgumentException invalid) {
        throw new IOException("Invalid runtime target in " + resource + " at " + prefix, invalid);
      }
      if ("METHOD".equals(kind) || "MEMBER".equals(kind)) {
        continue;
      }
      Map<String, TargetEntry> destination;
      if ("CLASS".equals(kind)) {
        destination = classes;
      } else if ("PACKAGE".equals(kind)) {
        destination = packages;
      } else {
        throw new IOException("Invalid runtime target entry kind " + kind + " in " + resource);
      }
      TargetEntry entry = new TargetEntry(target, origin);
      TargetEntry previous = destination.putIfAbsent(name, entry);
      if (previous != null && previous.target() != target) {
        throw new IOException(
            "Conflicting runtime targets for "
                + name
                + " from "
                + previous.origin()
                + " and "
                + origin);
      }
    }
  }

  private static Map<String, String> values(InputStream input) throws IOException {
    Map<String, String> result = new LinkedHashMap<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        int separator = line.indexOf('=');
        if (separator < 1) {
          throw new IOException("Invalid runtime target index line: " + line);
        }
        result.put(line.substring(0, separator), line.substring(separator + 1));
      }
    }
    return result;
  }

  private static int integer(Map<String, String> values, String key, String resource)
      throws IOException {
    try {
      return Integer.parseInt(required(values, key, resource));
    } catch (NumberFormatException invalid) {
      throw new IOException("Invalid integer " + key + " in " + resource, invalid);
    }
  }

  private static String required(Map<String, String> values, String key, String resource)
      throws IOException {
    String value = values.get(key);
    if (value == null) {
      throw new IOException("Missing " + key + " in " + resource);
    }
    return value;
  }

  private static String decoded(String value) {
    return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
  }

  private enum Target {
    JVM,
    TEAVM
  }

  private record TargetEntry(Target target, String origin) {}
}
