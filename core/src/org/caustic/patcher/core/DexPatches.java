package org.caustic.patcher.core;

import java.io.*;
import java.util.*;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.writer.pool.DexPool;

/** Overlays contain only added classes and changed/added members of existing classes. */
public final class DexPatches {
    public static final String MIDI_PREFIX = "Lcom/singlecellsoftware/caustic/midi/";
    public static String methodKey(Method m) {
        StringBuilder s = new StringBuilder(m.getName()).append('(');
        for (CharSequence p : m.getParameterTypes()) s.append(p);
        return s.append(')').append(m.getReturnType()).toString();
    }
    public static String fieldKey(Field f) { return f.getName() + ":" + f.getType(); }
    public static ClassDef members(ClassDef type, Collection<? extends Field> fields, Collection<? extends Method> methods) {
        return new ImmutableClassDef(type.getType(), type.getAccessFlags(), type.getSuperclass(),
                type.getInterfaces(), type.getSourceFile(), type.getAnnotations(), fields, methods);
    }
    public static void apply(byte[] original, List<byte[]> overlays, boolean replaceMidi, File output) throws Exception {
        DexBackedDexFile dex = new DexBackedDexFile(null, original);
        Map<String, ClassDef> classes = new TreeMap<>();
        for (ClassDef c : dex.getClasses()) {
            if (!replaceMidi || !c.getType().startsWith(MIDI_PREFIX)) classes.put(c.getType(), c);
        }
        Set<String> changedMembers = new HashSet<>();
        for (byte[] bytes : overlays) {
            for (ClassDef patch : new DexBackedDexFile(null, bytes).getClasses()) {
                ClassDef old = classes.get(patch.getType());
                if (old == null) {
                    if (!changedMembers.add(patch.getType())) throw new IOException("Conflicting added class");
                    classes.put(patch.getType(), patch); continue;
                }
                if (changedMembers.contains(patch.getType())) throw new IOException("Conflicting class overlay");
                Map<String, Method> methods = new TreeMap<>();
                Map<String, Field> fields = new TreeMap<>();
                for (Method m : old.getMethods()) methods.put(methodKey(m), m);
                for (Field f : old.getFields()) fields.put(fieldKey(f), f);
                for (Method m : patch.getMethods()) {
                    if (!changedMembers.add(patch.getType() + "->" + methodKey(m))) throw new IOException("Patches modify the same method");
                    methods.put(methodKey(m), m);
                }
                for (Field f : patch.getFields()) {
                    if (!changedMembers.add(patch.getType() + "->" + fieldKey(f))) throw new IOException("Patches modify the same field");
                    fields.put(fieldKey(f), f);
                }
                classes.put(patch.getType(), members(old, fields.values(), methods.values()));
            }
        }
        DexPool.writeTo(output.getPath(), new ImmutableDexFile(Opcodes.forApi(30), classes.values()));
    }
}
