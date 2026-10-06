package org.caustic.patcher;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;
import org.caustic.patcher.core.*;

public final class PatchService extends Service {
    public static volatile boolean busy;
    public static volatile String message = "Choose the official APK to begin.";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("patcher", MODE_PRIVATE); }
    public static File keyFile(Context c) { return new File(c.getFilesDir(), "signing.p12"); }
    public static File output(Context c) { return new File(c.getFilesDir(), "patched.apk"); }
    private void progress(String text) {
        message = text;
        getSystemService(NotificationManager.class).notify(1, notification(text));
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "patching").setSmallIcon(org.caustic.patcher.R.drawable.ic_patcher)
                .setContentTitle("Caustic 3 Patcher").setContentText(text).setContentIntent(open).setOngoing(true).build();
    }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("patching", "Patching and backups", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        if (busy) return START_NOT_STICKY;
        busy = true; message = "Working…";
        startForeground(1, notification(message));
        executor.execute(() -> {
            try { runTask(intent); }
            catch (Exception e) { message = "Could not complete: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
            catch (OutOfMemoryError e) { message = "Not enough memory to finish. Close other apps and try again."; }
            finally {
                new File(getFilesDir(), "patched.pending.apk").delete();
                prefs(this).edit().putString("lastStatus", message).commit();
                new Handler(Looper.getMainLooper()).post(() -> {
                    busy = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
                });
            }
        });
        return START_NOT_STICKY;
    }
    private static java.util.List<String> selectedPatches(boolean midi, boolean audio, boolean graphics) {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (midi) names.add("MIDI");
        if (audio) names.add("AAudio");
        if (graphics) names.add("Graphics pacing");
        return names;
    }
    private SigningKeys.Identity identity() throws Exception {
        SigningKeys.Identity key = SigningKeys.loadOrCreate(keyFile(this));
        prefs(this).edit().putString("fingerprint", key.fingerprint()).commit();
        return key;
    }
    private void runTask(Intent task) throws Exception {
        String action = task.getAction();
        if ("key".equals(action)) { identity(); message = "Local signing key ready. Export a backup before installing."; return; }
        Uri uri = task.getData();
        if ("verify".equals(action)) {
            prefs(this).edit().putBoolean("verified", false).commit();
            File source = new File(getFilesDir(), "original.apk");
            progress("Copying and checking APK…");
            try {
                if (getFilesDir().getUsableSpace() < 300L * 1024 * 1024) throw new IOException("At least 300 MB of free storage is needed.");
                try (InputStream in = requireInput(uri); OutputStream out = new FileOutputStream(source)) {
                    PatchEngine.copy(in, out, PatchEngine.ORIGINAL_SIZE);
                }
                PatchEngine.verifyOriginal(source);
                prefs(this).edit().putBoolean("verified", true).commit();
                message = "Verified: official Caustic 3.2.2 64-bit APK.";
            } catch (Exception e) { source.delete(); throw e; }
            return;
        }
        if ("patch".equals(action)) {
            if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) throw new IOException("These patches require an ARM64 device.");
            File pending = new File(getFilesDir(), "patched.pending.apk");
            boolean midi = task.getBooleanExtra("midi", false), audio = task.getBooleanExtra("audio", false);
            boolean graphics = task.getBooleanExtra("graphics", false);
            prefs(this).edit().remove("resultHash").commit();
            if (getFilesDir().getUsableSpace() < 200L * 1024 * 1024) throw new IOException("At least 200 MB of free storage is needed.");
            SigningKeys.Identity key = identity();
            PatchEngine.patch(new File(getFilesDir(), "original.apk"), new File(getCacheDir(), "patch-work"), pending,
                    midi, audio, graphics, name -> getAssets().open("patches/" + name), key, this::progress);
            String hash = PatchEngine.hash(pending);
            Files.move(pending.toPath(), output(this).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            prefs(this).edit().putString("resultHash", hash).putString("resultSigner", key.fingerprint())
                    .putString("resultPatches", String.join(" + ", selectedPatches(midi, audio, graphics))).commit();
            message = "Patched APK verified and ready to save or install."; return;
        }
        if ("save".equals(action)) {
            if (!PatchEngine.hash(output(this)).equals(prefs(this).getString("resultHash", ""))) throw new IOException("Output changed; patch again.");
            try (InputStream in = new FileInputStream(output(this)); OutputStream out = requireOutput(uri)) {
                PatchEngine.copy(in, out, 100L * 1024 * 1024);
            }
            message = "Patched APK saved."; return;
        }
        char[] password = task.getStringExtra("password").toCharArray();
        task.removeExtra("password");
        try {
            if ("export".equals(action)) {
                SigningKeys.Identity key = identity();
                try (OutputStream out = requireOutput(uri)) { SigningKeys.write(out, key, password); }
                prefs(this).edit().putString("backedUp", key.fingerprint()).commit();
                message = "Signing key exported. Keep the file and its password somewhere safe.";
            } else if ("import".equals(action)) {
                SigningKeys.Identity key;
                byte[] data = PatchEngine.read(requireInput(uri), 1024 * 1024);
                key = SigningKeys.read(new ByteArrayInputStream(data), password);
                SigningKeys.saveLocal(keyFile(this), key);
                prefs(this).edit().putString("fingerprint", key.fingerprint()).putString("backedUp", key.fingerprint()).commit();
                message = "Signing key imported. Existing APKs retain their original signing identity.";
            } else throw new IOException("Unknown operation");
        } finally { Arrays.fill(password, '\0'); }
    }
    private InputStream requireInput(Uri uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Cannot open selected file"); return in;
    }
    private OutputStream requireOutput(Uri uri) throws IOException {
        OutputStream out = getContentResolver().openOutputStream(uri, "wt");
        if (out == null) throw new IOException("Cannot write selected file"); return out;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { executor.shutdown(); super.onDestroy(); }
}
