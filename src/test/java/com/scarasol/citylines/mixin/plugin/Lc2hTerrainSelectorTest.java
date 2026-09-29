package com.scarasol.citylines.mixin.plugin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

class Lc2hTerrainSelectorTest {
    private static ClassNode target() {
        ClassNode owner = new ClassNode();
        owner.name = Lc2hTerrainSelector.TARGET;
        return owner;
    }

    @Test
    void instructionIdentityIgnoresGeneratedNamesButRejectsAmbiguity() {
        ClassNode owner = target();
        MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE, "arbitraryName", "()Z", null, null);
        helper.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, owner.name,
                "LC2H_PRESERVE_NATURAL_MOUNTAINS", "Z"));
        owner.methods.add(helper);
        assertSame(helper, Lc2hTerrainSelector.select(owner, "natural"));
        helper.name = "handler$changed$again";
        assertSame(helper, Lc2hTerrainSelector.select(owner, "natural"));
        helper.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner.name,
                "LC2H_PRESERVE_NATURAL_MOUNTAINS", "Z"));
        assertSame(helper, Lc2hTerrainSelector.select(owner, "natural"));
        helper.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, owner.name,
                "LC2H_PRESERVE_NATURAL_MOUNTAINS", "Z"));
        assertThrows(IllegalStateException.class, () -> Lc2hTerrainSelector.select(owner, "natural"));
        assertThrows(IllegalStateException.class, () -> Lc2hTerrainSelector.select(owner, "interior"));
    }

    @Test
    void gpuUsesTheCompleteDescriptorAndExactOwner() {
        ClassNode owner = target();
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, "unknownHandler", "()V", null, null);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Lc2hTerrainSelector.GPU,
                "tryCorrect", Lc2hTerrainSelector.GPU_DESC, false));
        owner.methods.add(method);
        assertSame(method, Lc2hTerrainSelector.select(owner, "gpu"));
        owner.name = "another/Target";
        assertThrows(IllegalStateException.class, () -> Lc2hTerrainSelector.select(owner, "gpu"));
    }
}
