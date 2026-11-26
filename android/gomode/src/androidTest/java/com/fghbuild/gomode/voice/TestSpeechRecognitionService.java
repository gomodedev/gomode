// Supplies platform speech readiness without a downloaded ASR model, and recognizes tones in supplied PCM.
package com.fghbuild.gomode.voice;

import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;

// This service runs in the test APK's own process, which cannot load the target APK's Kotlin runtime.
// Supplied 16 kHz PCM that carries a tone louder than -20 dBFS for 200 ms, then 300 ms of quieter
// audio, is recognized as "tone <Hz>", rounded to 100 Hz; other audio is never recognized.
public final class TestSpeechRecognitionService extends RecognitionService {
    private static final String TAG = "GoModeTestRecognizer";
    private static final int CHUNK_BYTES = 640; // 20 ms of 16 kHz mono 16-bit PCM.
    private static final int TONE_CHUNKS = 10;
    private static final int SILENCE_CHUNKS = 15;

    private Thread tones;

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        stopTones();
        ParcelFileDescriptor audio =
                recognizerIntent.getParcelableExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, ParcelFileDescriptor.class);
        if (audio != null) {
            tones = new Thread(() -> recognizeTones(audio, listener), "test-recognizer-tones");
            tones.start();
        }
        try {
            listener.readyForSpeech(new Bundle());
        } catch (android.os.RemoteException failure) {
            throw new IllegalStateException("Speech client disconnected before readiness", failure);
        }
    }

    @Override
    protected void onStopListening(Callback listener) {
        stopTones();
        Bundle result = new Bundle();
        result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, new ArrayList<>());
        try {
            listener.results(result);
        } catch (android.os.RemoteException failure) {
            throw new IllegalStateException("Speech client disconnected before results", failure);
        }
    }

    @Override
    protected void onCancel(Callback listener) {
        stopTones();
    }

    // The client ends the audio by closing its write end, which unblocks the reader.
    private void stopTones() {
        if (tones != null) tones.interrupt();
        tones = null;
    }

    private static void recognizeTones(ParcelFileDescriptor audio, Callback listener) {
        byte[] chunk = new byte[CHUNK_BYTES];
        String heard = null;
        int toneHz = 0;
        int toneChunks = 0;
        int silentChunks = 0;
        try (FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(audio)) {
            while (!Thread.currentThread().isInterrupted() && readChunk(input, chunk)) {
                int hz = toneHz(chunk);
                if (hz == 0) {
                    toneChunks = 0;
                    if (heard != null && ++silentChunks == SILENCE_CHUNKS) {
                        listener.results(results(heard));
                        return;
                    }
                    continue;
                }
                silentChunks = 0;
                toneChunks = hz == toneHz ? toneChunks + 1 : 1;
                toneHz = hz;
                if (toneChunks == TONE_CHUNKS && heard == null) {
                    heard = "tone " + hz;
                    listener.partialResults(results(heard));
                }
            }
        } catch (IOException | android.os.RemoteException failure) {
            Log.i(TAG, "Recognition audio ended: " + failure);
        }
    }

    private static boolean readChunk(FileInputStream input, byte[] chunk) throws IOException {
        int size = 0;
        while (size < chunk.length) {
            int count = input.read(chunk, size, chunk.length - size);
            if (count < 0) return false;
            size += count;
        }
        return true;
    }

    // Estimates a loud tone's frequency from zero crossings; 0 means quieter audio.
    private static int toneHz(byte[] pcm) {
        int samples = pcm.length / 2;
        double squares = 0;
        int crossings = 0;
        short previous = 0;
        for (int index = 0; index < samples; index++) {
            short sample = (short) ((pcm[2 * index] & 0xff) | (pcm[2 * index + 1] << 8));
            squares += (double) sample * sample;
            if (index > 0 && (previous < 0) != (sample < 0)) crossings++;
            previous = sample;
        }
        if (Math.sqrt(squares / samples) < 3277) return 0;
        double hz = crossings * 16_000.0 / (2.0 * samples);
        return (int) Math.round(hz / 100) * 100;
    }

    private static Bundle results(String text) {
        ArrayList<String> hypotheses = new ArrayList<>();
        hypotheses.add(text);
        Bundle bundle = new Bundle();
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, hypotheses);
        return bundle;
    }
}
