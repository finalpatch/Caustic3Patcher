import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.caustic.patcher.core.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;

public final class PatcherIntegrationTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private interface Task { void run() throws Exception; }
    private static void rejects(Task task, String message) throws Exception {
        try { task.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        File original = new File(args[0]); File work = new File(args[1]); work.mkdirs();
        File assets = new File("app/src/main/assets/patches");
        PatchEngine.Assets source = name -> new FileInputStream(new File(assets, name));
        File keyFile = new File(work, "test-key.p12");
        SigningKeys.Identity key = SigningKeys.loadOrCreate(keyFile);
        check(key.fingerprint().equals(SigningKeys.loadOrCreate(keyFile).fingerprint()), "identity must persist");
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        char[] password = "test-only-backup-password".toCharArray();
        SigningKeys.write(backup, key, password);
        SigningKeys.Identity imported = SigningKeys.read(new ByteArrayInputStream(backup.toByteArray()), password);
        check(key.fingerprint().equals(imported.fingerprint()), "backup round trip");
        rejects(() -> SigningKeys.read(new ByteArrayInputStream(backup.toByteArray()), "incorrect".toCharArray()), "bad password accepted");
        File badKey = new File(work, "corrupt-key.p12"); Files.write(badKey.toPath(), new byte[]{1,2,3});
        rejects(() -> SigningKeys.loadOrCreate(badKey), "corrupt key silently regenerated");
        check(Arrays.equals(Files.readAllBytes(badKey.toPath()), new byte[]{1,2,3}), "corrupt key overwritten");
        File badApk = new File(work, "bad.apk"); Files.write(badApk.toPath(), new byte[]{1,2,3});
        rejects(() -> PatchEngine.verifyOriginal(badApk), "bad input accepted");
        File sameSize = new File(work, "same-size-wrong.apk");
        try (RandomAccessFile f = new RandomAccessFile(sameSize, "rw")) { f.setLength(PatchEngine.ORIGINAL_SIZE); }
        rejects(() -> PatchEngine.verifyOriginal(sameSize), "same-size bad hash accepted"); sameSize.delete();
        rejects(() -> PatchEngine.patch(original, work, new File(work, "none.apk"), false, false, false, source, key, s -> {}), "empty selection accepted");
        rejects(() -> PatchEngine.patch(original, work, new File(work, "tampered.apk"), true, false, false,
                name -> name.equals("midi.dex") ? new ByteArrayInputStream(new byte[]{0}) : source.open(name), key, s -> {}), "tampered overlay accepted");
        byte[] originalDex;
        byte[] originalEngine;
        try (ZipFile zip = new ZipFile(original)) {
            originalDex = PatchEngine.read(zip.getInputStream(zip.getEntry("classes.dex")), 16000000);
            originalEngine = PatchEngine.read(zip.getInputStream(zip.getEntry("lib/arm64-v8a/libcaustic.so")), 16000000);
        }
        byte[] labeledEngine = AudioLabelPatch.apply(originalEngine);
        check(originalEngine.length == labeledEngine.length, "label patch resized native engine");
        check(PatchEngine.sha256(labeledEngine).equals("d815864d7bd041d29bcedf776ed7e5b0efd334d8522fb2d7a9734c5cd95ea60f"), "unexpected labeled engine hash");
        int changedBytes = 0;
        for (int i = 0; i < originalEngine.length; i++) if (originalEngine[i] != labeledEngine[i]) {
            if (i < 0x44bed || i >= 0x44bed + 9) throw new AssertionError("native code or unrelated data changed");
            changedBytes++;
        }
        check(changedBytes == 9, "unexpected native byte change count");
        check(new String(labeledEngine, 0x44bed, 10, java.nio.charset.StandardCharsets.US_ASCII).equals("AAudio\0\0\0\0"), "wrong option label");
        rejects(() -> AudioLabelPatch.apply(labeledEngine), "already modified engine accepted");
        byte[] corruptEngine = originalEngine.clone(); corruptEngine[0] ^= 1;
        rejects(() -> AudioLabelPatch.apply(corruptEngine), "unapproved engine accepted");
        for (byte[] input : new byte[][]{originalEngine, labeledEngine}) {
            byte[] snapshot = input.clone();
            byte[] paced = GraphicsPacingPatch.apply(input);
            check(paced.length == input.length, "graphics patch resized engine");
            byte[] expected = input.clone();
            System.arraycopy(new byte[]{0x0e, 0, 0, 0x14}, 0, expected, 0x2eabd0, 4);
            check(Arrays.equals(expected, paced), "graphics patch changed unrelated bytes");
            check(Arrays.equals(input, snapshot), "input changed");
            int instruction = java.nio.ByteBuffer.wrap(paced, 0x2eabd0, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
            check(0x2eebd0 + ((instruction & 0x03ffffff) << 2) == 0x2eec08,
                    "graphics branch misses original timestamp store");
            rejects(() -> GraphicsPacingPatch.apply(paced), "repeated graphics patch accepted");
        }
        rejects(() -> GraphicsPacingPatch.apply(corruptEngine), "corrupt graphics engine accepted");
        rejects(() -> GraphicsPacingPatch.apply(new byte[0]), "empty graphics engine accepted");
        byte[] overlay = PatchEngine.read(source.open("aaudio.dex"), 16000000);
        boolean discovery = false, initialization = false;
        for (ClassDef c : new DexBackedDexFile(null, overlay).getClasses()) {
            if (!c.getType().equals("Lcom/singlecellsoftware/caustic/audio/AudioBackend;")) continue;
            for (org.jf.dexlib2.iface.Field field : c.getFields())
                check(!field.getName().contains("SHA256"), "runtime hash constant retained");
            for (org.jf.dexlib2.iface.Method method : c.getMethods()) {
                check(!method.getName().equals("hash"), "runtime hash helper retained");
                if (method.getImplementation() == null) continue;
                for (org.jf.dexlib2.iface.instruction.Instruction instruction : method.getImplementation().getInstructions()) {
                    if (!(instruction instanceof org.jf.dexlib2.iface.instruction.ReferenceInstruction)) continue;
                    org.jf.dexlib2.iface.reference.Reference ref =
                            ((org.jf.dexlib2.iface.instruction.ReferenceInstruction) instruction).getReference();
                    if (ref instanceof org.jf.dexlib2.iface.reference.MethodReference) {
                        org.jf.dexlib2.iface.reference.MethodReference called =
                                (org.jf.dexlib2.iface.reference.MethodReference) ref;
                        check(!called.getDefiningClass().equals("Ljava/security/MessageDigest;"), "runtime digest retained");
                        if (called.getName().equals("nativeLoadedLibraryPath")) discovery = true;
                        if (called.getName().equals("nativeInitialize")) initialization = true;
                    }
                }
            }
        }
        check(discovery && initialization, "native initialization guards removed");
        rejects(() -> DexPatches.apply(originalDex, Arrays.asList(overlay, overlay), false, new File(work, "conflict.dex")), "conflicting patches accepted");
        for (int mask = 1; mask <= 7; mask++) {
            boolean midi = (mask & 1) != 0, audio = (mask & 2) != 0, graphics = (mask & 4) != 0;
            byte[] expectedEngine = audio ? labeledEngine : originalEngine;
            if (graphics) expectedEngine = GraphicsPacingPatch.apply(expectedEngine);
            File output = new File(work, "variant-" + mask + ".apk");
            PatchEngine.patch(original, work, output, midi, audio, graphics, source, imported, System.out::println);
            try (ZipFile input = new ZipFile(original); ZipFile result = new ZipFile(output)) {
                check((result.getEntry("lib/arm64-v8a/libcaustic_audio.so") != null) == audio, "audio helper selection");
                check(result.getEntry("lib/armeabi-v7a/libcaustic.so") == null, "unsupported ABI retained");
                Enumeration<? extends ZipEntry> entries = input.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement(); String name = e.getName();
                    if (e.isDirectory() || name.startsWith("META-INF/") || name.equals("classes.dex")
                            || (name.startsWith("lib/") && !name.startsWith("lib/arm64-v8a/"))) continue;
                    check(result.getEntry(name) != null, "lost entry " + name);
                    if (name.equals("lib/arm64-v8a/libcaustic.so")) {
                        check(Arrays.equals(expectedEngine, PatchEngine.read(result.getInputStream(result.getEntry(name)), 16000000)), "native patch composition differs");
                        continue;
                    }
                    check(Arrays.equals(PatchEngine.read(input.getInputStream(e), 100000000),
                            PatchEngine.read(result.getInputStream(result.getEntry(name)), 100000000)), "changed original entry " + name);
                }
                byte[] dex = PatchEngine.read(result.getInputStream(result.getEntry("classes.dex")), 16000000);
                if (!midi && !audio) check(Arrays.equals(originalDex, dex), "graphics-only changed original DEX");
                boolean picker = false, backend = false;
                for (ClassDef c : new DexBackedDexFile(null, dex).getClasses()) {
                    if (c.getType().equals("Lcom/singlecellsoftware/caustic/midi/MidiSidekick;"))
                        for (org.jf.dexlib2.iface.Method m : c.getMethods()) if (m.getName().equals("StartWithPicker")) picker = true;
                    if (c.getType().equals("Lcom/singlecellsoftware/caustic/audio/AudioBackend;")) backend = true;
                }
                check(picker == midi, "MIDI selection not independent"); check(backend == audio, "AAudio selection not independent");
            }
            check(output.isFile(), "output missing");
        }
        System.out.println("PASS: " + checks + " integration checks; all seven variants signed and verified.");
    }
}
