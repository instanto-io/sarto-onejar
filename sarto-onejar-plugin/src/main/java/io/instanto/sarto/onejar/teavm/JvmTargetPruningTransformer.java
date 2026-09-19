/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.sarto.onejar.teavm;

import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;

/**
 * Prevents target-private JVM classes in a unified Sarto JAR from becoming TeaVM interface or
 * subclass dispatch candidates.
 *
 * <p>The application graph has already rejected direct edges to these classes. Neutralising their
 * type hierarchy closes TeaVM's whole-classpath virtual-dispatch edge as well. The versioned Sarto
 * runtime-target index is the authoritative source; package names have no effect.
 */
public final class JvmTargetPruningTransformer implements ClassHolderTransformer {
  private final RuntimeTargetIndex targets;

  public JvmTargetPruningTransformer() {
    this(Thread.currentThread().getContextClassLoader());
  }

  JvmTargetPruningTransformer(ClassLoader loader) {
    targets = RuntimeTargetIndex.load(loader);
  }

  @Override
  public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
    if (!targets.isJvm(cls.getName())) {
      return;
    }
    cls.getInterfaces().clear();
    cls.getGenericInterfaces().clear();
    cls.setParent(Object.class.getName());
    cls.setGenericParent(null);
    cls.removeAllMethods();
    cls.removeAllFields();
  }

  boolean jvmTargetClass(String className) {
    return targets.isJvm(className);
  }
}
