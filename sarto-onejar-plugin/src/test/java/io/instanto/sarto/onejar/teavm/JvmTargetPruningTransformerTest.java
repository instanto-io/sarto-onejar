/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.sarto.onejar.teavm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.teavm.model.ClassHolder;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.ValueType;

class JvmTargetPruningTransformerTest {
  private final JvmTargetPruningTransformer transformer = new JvmTargetPruningTransformer();

  @Test
  void neutralisesJvmClassHierarchyAndBytecode() {
    ClassHolder cls = new ClassHolder("io.instanto.example.jvm.JvmProvider");
    cls.setParent("io.instanto.example.PortableBase");
    cls.getInterfaces().add("io.instanto.example.PortableProvider");
    cls.addMethod(new MethodHolder(new MethodDescriptor("create", ValueType.VOID)));

    transformer.transformClass(cls, null);

    assertEquals(Object.class.getName(), cls.getParent());
    assertTrue(cls.getInterfaces().isEmpty());
    assertTrue(cls.getMethods().isEmpty());
  }

  @Test
  void leavesPortableTeaVmAndThirdPartyClassesAlone() {
    assertFalse(transformer.jvmTargetClass("io.instanto.example.Portable"));
    assertFalse(transformer.jvmTargetClass("io.instanto.example.teavm.Browser"));
    assertTrue(transformer.jvmTargetClass("third.party.jvm.Provider"));
    assertTrue(transformer.jvmTargetClass("io.instanto.example.jvm.JreProvider"));
    assertFalse(transformer.jvmTargetClass("unindexed.jvm.Provider"));
  }
}
