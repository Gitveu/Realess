package uwu.realness.gitveu.tiny;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * Ultra-compact escalating stereo locator tone player for tiny-realness (works on ALL Bluetooth headsets).
 */
final class FindTone {
    static final int CH_LEFT = 0;
    static final int CH_BOTH = 1;
    static final int CH_RIGHT = 2;

    private final AudioManager am;
    private volatile boolean playing = false;
    private volatile int channel = CH_BOTH;
    private Thread thread;
    private AudioTrack track;
    private Integer savedVol;

    FindTone(Context ctx) {
        this.am = (AudioManager) ctx.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    boolean isPlaying() { return playing; }
    int getChannel() { return channel; }

    void setChannel(int ch) {
        channel = ch;
        applyPan(1f);
    }

    void start(int ch) {
        stop();
        channel = ch;
        playing = true;
        final int maxVol = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        savedVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        thread = new Thread(new Runnable() {
            @Override public void run() {
                int sr = 44100;
                short[] pcm = buildPattern(sr);
                int minBuf = Math.max(sr / 2, AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT));
                AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                    .setBufferSizeInBytes(Math.max(minBuf, pcm.length * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
                track = t;
                long t0 = System.currentTimeMillis();
                try {
                    applyPan(0.25f);
                    t.play();
                    int cursor = 0, chunk = 4410;
                    while (playing) {
                        float elapsed = Math.max(0L, System.currentTimeMillis() - t0);
                        float p = Math.min(1f, 0.15f + 0.85f * (elapsed / 20000f));
                        int sv = Math.max(1, Math.min(maxVol, Math.round(p * maxVol)));
                        try {
                            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != sv) {
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, sv, 0);
                            }
                        } catch (SecurityException ignored) {}
                        applyPan(0.35f + 0.65f * p);
                        int n = Math.min(chunk, pcm.length - cursor);
                        if (t.write(pcm, cursor, n) < 0) break;
                        cursor += n;
                        if (cursor >= pcm.length) cursor = 0;
                    }
                } catch (Exception ignored) {
                } finally {
                    try { t.stop(); } catch (Exception ignored) {}
                    try { t.release(); } catch (Exception ignored) {}
                    if (track == t) track = null;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        playing = false;
        if (thread != null) { thread.interrupt(); thread = null; }
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.pause(); t.flush(); t.stop(); t.release(); } catch (Exception ignored) {}
        }
        if (savedVol != null) {
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0); } catch (Exception ignored) {}
            savedVol = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void applyPan(float g) {
        AudioTrack t = track;
        if (t == null) return;
        float l = channel == CH_RIGHT ? 0f : g;
        float r = channel == CH_LEFT ? 0f : g;
        try { t.setStereoVolume(l, r); } catch (Exception ignored) {}
    }

    private static short[] buildPattern(int sr) {
        int total = (int) (sr * 1.4f);
        short[] out = new short[total * 2];
        int chirp = (int) (sr * 0.14f), gap = (int) (sr * 0.09f), fade = (int) (sr * 0.008f);
        for (int c = 0; c < 3; c++) {
            double f0 = c == 2 ? 3450.0 : 2950.0;
            int start = c * (chirp + gap);
            for (int i = 0; i < chirp && start + i < total; i++) {
                float env = i < fade ? (i / (float) fade) : (i > chirp - fade ? ((chirp - i) / (float) fade) : 1f);
                double sec = i / (double) sr;
                double w = 0.72 * Math.sin(2.0 * Math.PI * f0 * sec) + 0.28 * Math.sin(2.0 * Math.PI * f0 * 1.18 * sec);
                short s = (short) Math.max(-32767, Math.min(32767, (int) (w * env * 30000.0)));
                out[(start + i) * 2] = s;
                out[(start + i) * 2 + 1] = s;
            }
        }
        return out;
    }
}
