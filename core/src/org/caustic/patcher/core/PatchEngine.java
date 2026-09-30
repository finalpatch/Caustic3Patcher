package org.caustic.patcher.core;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

public final class PatchEngine {
    public static final String ORIGINAL_SHA256 = "7cf80508530e041821ab04693c6fc7bbd1fcd4c4b598cef825d3fd212b568ebf";
    public static final long ORIGINAL_SIZE = 49120477L;
    public interface Assets { InputStream open(String name) throws IOException; }
    public interface Progress { void report(String message); }
    public static String sha256(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    public static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) { out.append(Character.forDigit((b >>> 4) & 15, 16)); out.append(Character.forDigit(b & 15, 16)); }
        return out.toString();
    }
    public static byte[] read(InputStream in, int limit) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (out.size() > limit - n) throw new IOException("File exceeds supported size");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
    public static void copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[65536]; int n; long total = 0;
        while ((n = in.read(buffer)) != -1) {
            total += n; if (total > limit) throw new IOException("File exceeds supported size");
            out.write(buffer, 0, n);
        }
    }
    public static void verifyOriginal(File file) throws Exception {
        if (file.length() != ORIGINAL_SIZE || !ORIGINAL_SHA256.equals(hash(file)))
            throw new IOException("Unsupported APK. Choose the unmodified official Caustic 3.2.2 64-bit release. Its SHA-256 must be " + ORIGINAL_SHA256 + ".");
    }
    private static byte[] asset(Assets assets, Properties manifest, String name) throws Exception {
        byte[] bytes = read(assets.open(name), 16 * 1024 * 1024);
        if (!sha256(bytes).equals(manifest.getProperty(name))) throw new IOException("Bundled patch integrity check failed: " + name);
        return bytes;
    }
    public static void patch(File original, File work, File output, boolean midi, boolean audio,
                             Assets assets, SigningKeys.Identity identity, Progress progress) throws Exception {
        if (!midi && !audio) throw new IOException("Select at least one patch.");
        progress.report("Verifying official APK…"); verifyOriginal(original);
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("Cannot create workspace");
        Properties manifest = new Properties();
        try (InputStream in = assets.open("checksums.properties")) { manifest.load(in); }
        List<byte[]> overlays = new ArrayList<>();
        if (midi) overlays.add(asset(assets, manifest, "midi.dex"));
        if (audio) overlays.add(asset(assets, manifest, "aaudio.dex"));
        byte[] helper = audio ? asset(assets, manifest, "libcaustic_audio.so") : null;
        File dex = new File(work, "classes.dex"), unsigned = new File(work, "unsigned.apk");
        boolean success = false;
        try (ZipFile input = new ZipFile(original)) {
            progress.report("Applying selected patches…");
            DexPatches.apply(read(input.getInputStream(input.getEntry("classes.dex")), 16 * 1024 * 1024), overlays, midi, dex);
            progress.report("Packaging APK…");
            try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(unsigned)))) {
                Set<String> names = new HashSet<>();
                Enumeration<? extends ZipEntry> entries = input.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement(); String name = e.getName();
                    if (!names.add(name)) throw new IOException("Duplicate APK entry");
                    if (e.isDirectory() || name.startsWith("META-INF/") || name.equals("classes.dex")
                            || (name.startsWith("lib/") && !name.startsWith("lib/arm64-v8a/"))) continue;
                    ZipEntry copy = new ZipEntry(name); copy.setTime(315532800000L);
                    // Native libraries and resources must remain uncompressed. Apksig performs alignment.
                    if (e.getMethod() == ZipEntry.STORED || name.endsWith(".so") || name.equals("resources.arsc")) {
                        copy.setMethod(ZipEntry.STORED); copy.setSize(e.getSize()); copy.setCompressedSize(e.getSize()); copy.setCrc(e.getCrc());
                    }
                    out.putNextEntry(copy);
                    try (InputStream stream = input.getInputStream(e)) { copy(stream, out, 100 * 1024 * 1024); }
                    out.closeEntry();
                }
                add(out, "classes.dex", read(new FileInputStream(dex), 16 * 1024 * 1024));
                if (audio) add(out, "lib/arm64-v8a/libcaustic_audio.so", helper);
            }
            progress.report("Signing with your local key…");
            ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder("caustic", identity.key,
                    Collections.singletonList(identity.certificate)).build();
            new ApkSigner.Builder(Collections.singletonList(config)).setInputApk(unsigned).setOutputApk(output)
                    .setMinSdkVersion(30).setV1SigningEnabled(false).setV2SigningEnabled(true)
                    .setV3SigningEnabled(true).setV4SigningEnabled(false).setAlignmentPreserved(false)
                    .setLibraryPageAlignmentBytes(16384).build().sign();
            progress.report("Verifying signed APK…");
            ApkVerifier.Result result = new ApkVerifier.Builder(output).setMinCheckedPlatformVersion(30).build().verify();
            if (!result.isVerified() || result.getSignerCertificates().size() != 1
                    || !Arrays.equals(result.getSignerCertificates().get(0).getEncoded(), identity.certificate.getEncoded()))
                throw new IOException("Output signature verification failed");
            success = true;
        } finally {
            dex.delete(); unsigned.delete();
            if (!success) output.delete();
        }
    }
    private static void add(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        ZipEntry e = new ZipEntry(name); e.setTime(315532800000L); e.setMethod(ZipEntry.STORED);
        CRC32 crc = new CRC32(); crc.update(bytes); e.setCrc(crc.getValue()); e.setSize(bytes.length); e.setCompressedSize(bytes.length);
        out.putNextEntry(e); out.write(bytes); out.closeEntry();
    }
}
