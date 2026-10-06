package org.caustic.patcher;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.caustic.patcher.core.PatchEngine;

public final class MainActivity extends Activity {
    private static final int PICK = 1, SAVE = 2, EXPORT = 3, IMPORT = 4;
    private static final String BACKUP = "First installation of patched Caustic requires uninstalling the original app because its signing certificate differs. Uninstalling can erase app data.\n\nBack up important songs, presets, samples and other files before uninstalling. Copy accessible Caustic files to a PC, or use Caustic’s built-in FTP server: main menu → Tools. Confirm you can open your backup. A PC connection does not expose all private app data.\n\nLater patched versions can update in place when you keep the same signing key. We will never uninstall Caustic for you.";
    private TextView status, source, keyInfo, result;
    private CheckBox midi, audio, graphics;
    private Button choose, patch, save, install, export, importKey;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() { public void run() { render(); handler.postDelayed(this, 500); } };
    private boolean launching;
    private long launchTime;
    private LinearLayout content;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(20), dp(24), dp(32)); content.setBackgroundColor(Color.rgb(247,249,247));
        scroll.addView(content); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        text("CAUSTIC 3", 14, true);
        text("Unofficial patcher", 30, true);
        text("Community fixes, shared with the author’s permission. This is not an official Caustic release.", 16, false);
        text("1 · Choose your APK", 21, true);
        text("Requires Android 11+ and ARM64. Only the exact official Caustic 3.2.2 64-bit APK is supported. Everything runs on this device.", 15, false);
        choose = button("Choose APK", () -> picker(PICK, false, "*/*", null));
        source = text("", 14, false);
        text("2 · Select fixes", 21, true);
        midi = check("MIDI crash fix", "midi");
        text("Replaces the MIDI input bridge with Android’s MIDI API. Includes a source picker and reconnect support.", 15, false);
        audio = check("AAudio playback path", "audio");
        text("Adds the “AAudio” choice in Caustic’s audio options, replacing OpenSL ES. Includes latency presets and route recovery. AudioTrack remains available. AAudio-only does not fix the MIDI crash.", 15, false);
        graphics = check("Graphics pacing", "graphics");
        text("Skips the busy-wait frame limiter and lets display presentation pace rendering, without a 60 FPS cap. Experimental; 120 Hz behavior is not yet tested.", 15, false);
        text("3 · Preserve your signing key", 21, true);
        text("Your key is created on this device and reused for updates. Export it before installing. Uninstalling this patcher or clearing its storage deletes the local key. Keep the backup and password; import them after reinstalling or moving devices.", 15, false);
        keyInfo = text("", 13, false);
        export = button("Export signing key backup", () -> picker(EXPORT, true, "application/octet-stream", "Caustic-patcher-signing-key.p12"));
        importKey = button("Import signing key backup", this::confirmImport);
        text("Before the first installation", 21, true);
        text(BACKUP, 15, false);
        patch = button("Apply selected patches", () -> {
            Intent task = new Intent(this, PatchService.class).setAction("patch")
                    .putExtra("midi", midi.isChecked()).putExtra("audio", audio.isChecked()).putExtra("graphics", graphics.isChecked());
            start(task);
        });
        status = text("", 16, true);
        result = text("", 13, false);
        save = button("Save patched APK", () -> picker(SAVE, true, "application/vnd.android.package-archive", "Caustic-3.2.2-unofficial.apk"));
        install = button("Install patched APK", this::offerInstall);
        button("About and third-party notices", () -> {
            try {
                String notices = new String(PatchEngine.read(getAssets().open("NOTICES.txt"), 200000), java.nio.charset.StandardCharsets.UTF_8);
                new AlertDialog.Builder(this).setTitle("About this patcher").setMessage("Experimental community patches. Device testing so far covers one Android 16 ARM64 device; broader audio, MIDI and export testing remains necessary.\n\n" + notices).setPositiveButton("Close", null).show();
            } catch (Exception e) { error(e.getMessage()); }
        });
        if (!PatchService.busy) PatchService.message = PatchService.prefs(this).getString("lastStatus", "Choose the official APK to begin.");
        if ((!PatchService.keyFile(this).exists() || !PatchService.prefs(this).contains("fingerprint")) && !PatchService.busy)
            start(new Intent(this, PatchService.class).setAction("key"));
    }
    private TextView text(String value, int size, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(Color.rgb(30,49,43));
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setPadding(0, dp(bold ? 18 : 8), 0, dp(8)); v.setTextIsSelectable(true); content.addView(v); return v;
    }
    private Button button(String title, Runnable action) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setOnClickListener(v -> action.run());
        content.addView(b, new LinearLayout.LayoutParams(-1, -2)); return b;
    }
    private CheckBox check(String title, String pref) {
        CheckBox c = new CheckBox(this); c.setText(title); c.setTextSize(18);
        c.setChecked(PatchService.prefs(this).getBoolean(pref, true));
        c.setOnCheckedChangeListener((view, checked) -> { PatchService.prefs(this).edit().putBoolean(pref, checked).apply(); render(); });
        content.addView(c); return c;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onStart() { super.onStart(); handler.post(refresh); }
    @Override protected void onStop() { handler.removeCallbacks(refresh); super.onStop(); }
    private void render() {
        if (status == null) return;
        if (launching && (PatchService.busy || SystemClock.elapsedRealtime() - launchTime > 3000)) launching = false;
        boolean idle = !launching && !PatchService.busy;
        SharedPreferences p = PatchService.prefs(this);
        boolean verified = p.getBoolean("verified", false);
        boolean ready = p.contains("resultHash") && PatchService.output(this).isFile();
        boolean arm = Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a");
        source.setText(verified ? "✓ Official APK hash verified" : "No verified APK selected");
        status.setText(arm ? PatchService.message : "This device is not ARM64. These patches are unsupported here.");
        String fingerprint = p.getString("fingerprint", "");
        keyInfo.setText(fingerprint.isEmpty() ? "Preparing local signing key…" : "Certificate SHA-256:\n" + fingerprint +
                (fingerprint.equals(p.getString("backedUp", "")) ? "\nBackup exported or imported." : "\nBackup not yet exported."));
        choose.setEnabled(idle); midi.setEnabled(idle); audio.setEnabled(idle); graphics.setEnabled(idle);
        patch.setEnabled(idle && arm && verified && !fingerprint.isEmpty() && (midi.isChecked() || audio.isChecked() || graphics.isChecked()));
        export.setEnabled(idle && !fingerprint.isEmpty()); importKey.setEnabled(idle);
        save.setEnabled(idle && ready); install.setEnabled(idle && ready && arm);
        result.setText(ready ? "Output: " + p.getString("resultPatches", "") + "\nAPK SHA-256:\n" + p.getString("resultHash", "") : "");
    }
    private void start(Intent task) {
        if (PatchService.busy || launching) return;
        launching = true; launchTime = SystemClock.elapsedRealtime();
        try { startForegroundService(task); }
        catch (Exception e) { launching = false; error(e.getMessage()); }
        render();
    }
    private void picker(int request, boolean create, String mime, String name) {
        Intent i = new Intent(create ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE);
        if (name != null) i.putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(i, request); } catch (ActivityNotFoundException e) { error("No system file picker is available."); }
    }
    private void confirmImport() {
        new AlertDialog.Builder(this).setTitle("Replace local signing key?")
                .setMessage("Export your current key first if you have used it. Importing replaces it only after the backup and password have been validated. A different key cannot update apps signed with your old key.")
                .setNegativeButton("Cancel", null).setPositiveButton("Choose backup", (d, w) -> picker(IMPORT, false, "*/*", null)).show();
    }
    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (code != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK || request == SAVE) start(new Intent(this, PatchService.class).setAction(request == PICK ? "verify" : "save").setData(uri));
        else if (request == EXPORT || request == IMPORT) password(request, uri);
    }
    private void password(int request, Uri uri) {
        EditText input = new EditText(this); input.setSingleLine(); input.setHint("Backup password");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(request == EXPORT ? "Protect your key backup" : "Unlock your key backup")
                .setMessage(request == EXPORT ? "Choose a password of at least 8 characters. Keep it safely: it cannot be recovered." : "Enter the password used when exporting this backup.")
                .setView(input).setNegativeButton("Cancel", null).setPositiveButton(request == EXPORT ? "Export" : "Import", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pass = input.getText().toString();
            if (request == EXPORT && pass.length() < 8) { input.setError("Use at least 8 characters"); return; }
            start(new Intent(this, PatchService.class).setAction(request == EXPORT ? "export" : "import").setData(uri).putExtra("password", pass));
            input.setText(""); dialog.dismiss();
        })); dialog.show();
    }
    private void offerInstall() {
        SharedPreferences p = PatchService.prefs(this);
        String signer = p.getString("resultSigner", "");
        if (!signer.equals(p.getString("backedUp", ""))) {
            new AlertDialog.Builder(this).setTitle("Back up your signing key")
                    .setMessage("Export the key used for this APK before installing. It is needed for future updates. If you changed keys after patching, restore the correct key or patch again.")
                    .setPositiveButton("OK", null).show(); return;
        }
        try {
            PackageInfo installed = getPackageManager().getPackageInfo("com.singlecellsoftware.caustic", PackageManager.GET_SIGNING_CERTIFICATES);
            boolean match = false;
            for (android.content.pm.Signature cert : installed.signingInfo.getApkContentsSigners())
                if (PatchEngine.sha256(cert.toByteArray()).equals(signer)) match = true;
            if (!match) {
                new AlertDialog.Builder(this).setTitle("Installed Caustic uses another key")
                        .setMessage(BACKUP + "\n\nIf this is already a patched copy, restore its signing-key backup and patch again instead of uninstalling.")
                        .setPositiveButton("Understood", null).show(); return;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            new AlertDialog.Builder(this).setTitle("Before installing Caustic").setMessage(BACKUP)
                    .setNegativeButton("Cancel", null).setPositiveButton("Continue", (d, w) -> installNow()).show(); return;
        } catch (Exception e) { error("Could not check installed signing identity: " + e.getMessage()); return; }
        installNow();
    }
    private void installNow() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this).setTitle("Allow installation")
                    .setMessage("Enable “Allow from this source” for this patcher, then return and tap Install patched APK again.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Open settings", (d, w) -> {
                        try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
                        catch (ActivityNotFoundException e) { error("Open Android settings and allow this patcher to install apps."); }
                    }).show(); return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(ApkProvider.URI, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(ClipData.newRawUri("Patched Caustic APK", ApkProvider.URI)); startActivity(i);
        } catch (ActivityNotFoundException e) { error("No APK installer is available. Save the APK and open it from your file manager."); }
    }
    private void error(String text) { new AlertDialog.Builder(this).setTitle("Caustic patcher").setMessage(text).setPositiveButton("OK", null).show(); }
}
