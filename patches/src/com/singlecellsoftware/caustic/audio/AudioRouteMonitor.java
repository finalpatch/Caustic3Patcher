package com.singlecellsoftware.caustic.audio;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import java.util.Arrays;

/** Process-lifetime output-topology listener; native worker debounces reopening. */
final class AudioRouteMonitor extends AudioDeviceCallback {
    private static AudioRouteMonitor instance;
    private final AudioManager manager;
    private int[] devices;

    private AudioRouteMonitor(AudioManager manager) {
        this.manager = manager;
        devices = currentDevices();
    }

    static void start(Context context) {
        if (instance != null) return;
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) throw new IllegalStateException("AudioManager unavailable");
        instance = new AudioRouteMonitor(manager);
        manager.registerAudioDeviceCallback(instance, new Handler(Looper.getMainLooper()));
    }

    private int[] currentDevices() {
        AudioDeviceInfo[] outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        int[] ids = new int[outputs.length];
        for (int i = 0; i < outputs.length; ++i) ids[i] = outputs[i].getId();
        Arrays.sort(ids);
        return ids;
    }

    private void changed() {
        int[] current = currentDevices();
        if (!Arrays.equals(devices, current)) {
            devices = current;
            AudioBackend.nativeRouteChanged();
        }
    }

    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { changed(); }
    @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { changed(); }
}
