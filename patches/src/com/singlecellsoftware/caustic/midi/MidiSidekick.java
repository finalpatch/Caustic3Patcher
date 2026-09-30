package com.singlecellsoftware.caustic.midi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Android MIDI transport, retaining Caustic's original activity and JNI contracts. */
public final class MidiSidekick {
    private static final String TAG = "CausticMidi";
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object gate = new Object();
    private final SharedPreferences preferences;
    private final int[][] notes = new int[16][128];
    private final boolean[] sustain = new boolean[16];
    private volatile boolean started;
    private int lifecycle;
    private int connection;
    // The following transport/UI fields are only accessed on the main thread.
    private MidiManager manager;
    private MidiManager.DeviceCallback callback;
    private android.media.midi.MidiDevice device;
    private MidiOutputPort output;
    private int pendingId = -1;
    private AlertDialog picker;
    private long packets;

    private static native void nativeSetMIDIDeviceConnected(boolean connected);

    public MidiSidekick(Activity activity) {
        this.activity = activity;
        preferences = activity.getSharedPreferences("android_midi_bridge", Context.MODE_PRIVATE);
    }

    public boolean IsStarted() { return started; }
    public void Start() { start(false); }
    public void StartWithPicker() { start(true); }

    private void start(final boolean choose) {
        synchronized (gate) {
            if (started) return;
            started = true;
            final int token = ++lifecycle;
            main.post(() -> {
                if (!current(token)) return;
                try {
                    manager = (MidiManager) activity.getSystemService(Context.MIDI_SERVICE);
                    if (manager == null) { status("MIDI is unavailable on this device"); return; }
                    callback = new MidiManager.DeviceCallback() {
                        @Override public void onDeviceAdded(MidiDeviceInfo info) {
                            if (current(token) && picker == null) reconnect(token);
                        }
                        @Override public void onDeviceRemoved(MidiDeviceInfo info) {
                            if (!current(token)) return;
                            if (info.getId() == pendingId || (device != null && device.getInfo().getId() == info.getId())) {
                                disconnect();
                                status("MIDI source disconnected; waiting to reconnect");
                            }
                        }
                    };
                    if (Build.VERSION.SDK_INT >= 33)
                        manager.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM,
                                command -> main.post(command), callback);
                    else manager.registerDeviceCallback(callback, main);
                    Log.i(TAG, "Started Android MIDI monitoring");
                    if (choose) showPicker(token);
                    else if (preferences.contains("source")) reconnect(token);
                    else status("MIDI enabled. Turn MIDI off and on to choose a source.");
                } catch (RuntimeException error) {
                    Log.e(TAG, "Unable to start MIDI", error);
                    status("MIDI could not start. Turn MIDI off and on to retry.");
                }
            });
        }
    }

    public void Stop() {
        synchronized (gate) {
            if (!started) return;
            started = false;
            ++lifecycle; // Invalidate input synchronously, even when called by GL thread.
            main.post(() -> {
                if (picker != null) { picker.dismiss(); picker = null; }
                disconnect();
                if (manager != null && callback != null) {
                    try { manager.unregisterDeviceCallback(callback); }
                    catch (RuntimeException error) { Log.w(TAG, "Callback cleanup", error); }
                }
                callback = null;
                manager = null;
                Log.i(TAG, "Stopped Android MIDI monitoring");
            });
        }
    }

    private boolean current(int token) {
        synchronized (gate) { return started && lifecycle == token; }
    }

    private List<Source> sources() {
        List<Source> result = new ArrayList<>();
        Iterable<MidiDeviceInfo> devices = Build.VERSION.SDK_INT >= 33
                ? manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)
                : Arrays.asList(manager.getDevices());
        for (MidiDeviceInfo info : devices) for (MidiDeviceInfo.PortInfo port : info.getPorts()) {
            if (port.getType() == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) result.add(new Source(info, port));
        }
        Collections.sort(result, Comparator.comparing(source -> source.label));
        return result;
    }

    private void showPicker(int token) {
        if (!current(token) || activity.isFinishing() || activity.isDestroyed()) return;
        List<Source> available = sources();
        // Explicit selection starts disconnected. Cancel must not reconnect an old choice.
        preferences.edit().remove("source").apply();
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("MIDI input source")
                .setNegativeButton("Cancel", (ignored, which) -> status("MIDI enabled; no source selected"));
        if (available.isEmpty()) {
            dialog.setMessage("No MIDI sources are available. Connect a USB controller or open a virtual MIDI source, then tap Refresh.");
        } else {
            String[] labels = new String[available.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = available.get(i).label;
            dialog.setItems(labels, (ignored, index) -> {
                if (!current(token)) return;
                Source source = available.get(index);
                preferences.edit().putString("source", source.key).apply();
                open(source, token);
            });
        }
        dialog.setNeutralButton("Refresh", (ignored, which) -> main.post(() -> {
            if (current(token)) showPicker(token);
        }));
        picker = dialog.create();
        picker.setOnDismissListener(ignored -> picker = null);
        picker.setOnCancelListener(ignored -> status("MIDI enabled; no source selected"));
        picker.show();
    }

    private void reconnect(int token) {
        if (!current(token) || manager == null || device != null || pendingId != -1 || picker != null) return;
        String key = preferences.getString("source", null);
        if (key == null) return;
        try {
            List<Source> matches = new ArrayList<>();
            for (Source source : sources()) if (source.key.equals(key)) matches.add(source);
            if (matches.size() == 1) open(matches.get(0), token);
            else if (matches.size() > 1) status("Matching MIDI sources found. Turn MIDI off and on to choose.");
            else status("MIDI enabled; waiting for the saved source");
        } catch (RuntimeException error) {
            Log.e(TAG, "MIDI enumeration failed", error);
            status("MIDI sources could not be listed");
        }
    }

    private void open(Source source, int token) {
        disconnect();
        final int epoch;
        synchronized (gate) { epoch = connection; }
        pendingId = source.info.getId();
        Log.i(TAG, "Opening " + source.label + " device=" + pendingId + " port=" + source.port);
        main.postDelayed(() -> {
            if (current(token) && connection == epoch && pendingId != -1) {
                disconnect(); status("MIDI source did not respond. Turn MIDI off and on to retry.");
            }
        }, 5000);
        try {
            manager.openDevice(source.info, opened -> {
                if (!current(token) || epoch != connection) { close(opened); return; }
                pendingId = -1;
                if (opened == null) { status("Could not open MIDI source. Turn MIDI off and on to retry."); return; }
                device = opened;
                try {
                    output = opened.openOutputPort(source.port);
                    if (output == null) throw new IllegalStateException("MIDI output port unavailable");
                    MidiStreamParser parser = new MidiStreamParser(this::deliver);
                    output.connect(new MidiReceiver() {
                        @Override public void onSend(byte[] data, int offset, int count, long timestamp) {
                            synchronized (gate) {
                                if (!started || lifecycle != token || connection != epoch) return;
                                try { parser.accept(data, offset, count); }
                                catch (RuntimeException error) {
                                    Log.e(TAG, "MIDI input failed", error);
                                    ++connection; // Reject further input before main-thread cleanup.
                                    main.post(() -> {
                                        if (current(token) && device == opened) {
                                            disconnect(); status("MIDI input stopped. Turn MIDI off and on to retry.");
                                        }
                                    });
                                }
                            }
                        }
                        @Override public void onFlush() {
                            synchronized (gate) {
                                if (!started || lifecycle != token || connection != epoch) return;
                                parser.reset(); releaseNotes();
                            }
                        }
                    });
                    synchronized (gate) { nativeSetMIDIDeviceConnected(true); }
                    status("MIDI connected: " + source.label);
                } catch (RuntimeException error) {
                    Log.e(TAG, "MIDI port open failed", error);
                    disconnect(); status("Could not open MIDI port. Turn MIDI off and on to retry.");
                }
            }, main);
        } catch (RuntimeException error) {
            Log.e(TAG, "MIDI device open failed", error);
            disconnect(); status("Could not open MIDI source. Turn MIDI off and on to retry.");
        }
    }

    // Called synchronously under gate: parser buffers and JNI delivery never overlap.
    private void deliver(byte[] packet) {
        MidiDevice.sendPacket(packet);
        int type = packet[1] & 0xf0, channel = packet[1] & 15, note = packet[2] & 127;
        if (type == 0x90 && packet[3] != 0) notes[channel][note] = Math.min(127, notes[channel][note] + 1);
        else if (type == 0x80 || type == 0x90) notes[channel][note] = Math.max(0, notes[channel][note] - 1);
        else if (type == 0xb0 && note == 64) sustain[channel] = (packet[3] & 127) >= 64;
        if (++packets == 1) Log.i(TAG, "First MIDI packet delivered to native engine");
    }

    private void releaseNotes() {
        byte[] packet = new byte[4];
        for (int channel = 0; channel < 16; channel++) {
            if (sustain[channel]) {
                packet[0] = 11; packet[1] = (byte) (0xb0 | channel); packet[2] = 64; packet[3] = 0;
                MidiDevice.sendPacket(packet); sustain[channel] = false;
            }
            packet[0] = 8; packet[1] = (byte) (0x80 | channel); packet[3] = 0;
            for (int note = 0; note < 128; note++) {
                packet[2] = (byte) note;
                while (notes[channel][note] > 0) { MidiDevice.sendPacket(packet); notes[channel][note]--; }
            }
        }
    }

    private void disconnect() {
        synchronized (gate) {
            ++connection;
            releaseNotes();
            nativeSetMIDIDeviceConnected(false);
            if (packets > 0) Log.i(TAG, "Disconnected after " + packets + " MIDI packets");
            packets = 0;
        }
        close(output); output = null;
        close(device); device = null;
        pendingId = -1;
    }

    private static void close(Closeable resource) {
        if (resource == null) return;
        try { resource.close(); }
        catch (Exception error) { Log.w(TAG, "MIDI close failed", error); }
    }

    private void status(String message) {
        Log.i(TAG, message);
        if (!activity.isFinishing() && !activity.isDestroyed())
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private static final class Source {
        final MidiDeviceInfo info;
        final int port;
        final String key, label;
        Source(MidiDeviceInfo info, MidiDeviceInfo.PortInfo portInfo) {
            this.info = info; port = portInfo.getPortNumber();
            Bundle properties = info.getProperties();
            String name = properties.getString(MidiDeviceInfo.PROPERTY_NAME, "MIDI device");
            String portName = portInfo.getName();
            label = name + " — " + (portName == null || portName.isEmpty() ? "Port " + (port + 1) : portName)
                    + " [" + info.getId() + ":" + (port + 1) + "]";
            StringBuilder identity = new StringBuilder().append(info.getType()).append('/').append(port);
            for (String property : new String[]{MidiDeviceInfo.PROPERTY_NAME, MidiDeviceInfo.PROPERTY_MANUFACTURER,
                    MidiDeviceInfo.PROPERTY_PRODUCT, MidiDeviceInfo.PROPERTY_SERIAL_NUMBER}) {
                String value = properties.getString(property, "");
                identity.append('/').append(value.length()).append(':').append(value);
            }
            Object service = properties.get("service_info");
            if (service instanceof ServiceInfo) {
                ServiceInfo entry = (ServiceInfo) service;
                identity.append('/').append(entry.packageName).append('/').append(entry.name);
            }
            key = identity.toString();
        }
    }
}
