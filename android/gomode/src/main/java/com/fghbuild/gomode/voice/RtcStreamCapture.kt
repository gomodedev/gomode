// Transmits a selected voice audio stream on the WebRTC microphone track in place of the phone microphone.
//
// stream-webrtc-android 1.3.10 offers no input other than the phone microphone: JavaAudioDeviceModule's
// Builder stores an AudioRecordDataCallback but never hands it to WebRtcAudioRecord, and SamplesReadyCallback
// receives a copy (stream-webrtc-android/src/main/java/org/webrtc/audio/JavaAudioDeviceModule.java and
// WebRtcAudioRecord.java at tag 1.3.10, https://github.com/GetStream/webrtc-android). Its capture
// post-processor can write the captured audio: AudioProcessingImpl::ProcessCaptureStreamLocked runs it after
// echo cancellation, noise suppression, and gain control, before the encoder
// (modules/audio_processing/audio_processing_impl.cc), and ExternalAudioProcessor::Process passes channel 0,
// num_frames FloatS16 samples covering 10 ms, so the rate is num_frames * 100
// (extensions/android/audio-processing/src/external_audio_processor.cc, https://github.com/GetStream/webrtc).
//
// The audio device module therefore keeps the phone microphone open as the capture clock, with hardware
// AEC and NS, and the post-processor overwrites each 10 ms with the stream, resampled linearly from 16 kHz.
// Ducking then applies as it does to the phone microphone. The stream's thread writes and the audio thread
// reads: reading starts once JITTER_MS is buffered and restarts that way after the stream runs dry, which
// sends silence; a stream faster than the capture clock loses its oldest samples beyond CAPACITY_MS.
package com.fghbuild.gomode.voice

import org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

// TODO(hardware): Confirm that 40 ms absorbs the delivery jitter of a real stream, and that 160 ms bounds
// its clock drift against the capture clock. No stream exists yet; the session logs each stream's underruns
// and dropped samples when it closes (RtcMicrophoneProcessing.finish). Frequent underruns call for a larger
// JITTER_MS; steadily dropped samples call for resampling to the measured stream rate.
private const val JITTER_MS = 40
private const val CAPACITY_MS = 160

internal class RtcStreamCapture : PcmSink {
    private val ring = ShortArray(VOICE_AUDIO_RATE_HZ * CAPACITY_MS / 1_000)
    private var head = 0
    private var size = 0
    private var reading = false
    private var underruns = 0
    private var dropped = 0

    // Audio-thread interpolation between the two stream samples around the next output sample.
    private var previous = 0f
    private var next = 0f
    private var phase = 0.0

    override fun write(
        pcm: ByteArray,
        count: Int,
    ) {
        synchronized(this) {
            for (offset in 0 until count step 2) {
                if (size == ring.size) {
                    head = (head + 1) % ring.size
                    size--
                    dropped++
                }
                ring[(head + size) % ring.size] =
                    ((pcm[offset].toInt() and 0xff) or (pcm[offset + 1].toInt() shl 8)).toShort()
                size++
            }
        }
    }

    /** read overwrites [frames] native-endian FloatS16 samples of [buffer] with the stream at [rateHz]. */
    fun read(
        buffer: ByteBuffer,
        frames: Int,
        rateHz: Int,
    ) {
        val step = VOICE_AUDIO_RATE_HZ.toDouble() / rateHz
        synchronized(this) {
            if (!reading && size >= VOICE_AUDIO_RATE_HZ * JITTER_MS / 1_000) reading = true
            for (index in 0 until frames) {
                buffer.putFloat(index * Float.SIZE_BYTES, previous + (next - previous) * phase.toFloat())
                phase += step
                while (phase >= 1.0) {
                    phase -= 1.0
                    previous = next
                    next = take()
                }
            }
        }
    }

    /** describe reports how often the stream ran dry and how many samples it lost to overflow. */
    fun describe(): String = synchronized(this) { "$underruns underruns, $dropped dropped samples" }

    private fun take(): Float {
        if (reading && size == 0) {
            reading = false
            underruns++
        }
        if (!reading) return 0f
        val sample = ring[head]
        head = (head + 1) % ring.size
        size--
        return sample.toFloat()
    }
}

/** RtcCaptureProcessing replaces the processed capture with [stream] when one is set, then ducks it. */
internal class RtcCaptureProcessing(
    private val stream: () -> RtcStreamCapture?,
    private val ducking: MicrophoneDucking,
) : ExternalAudioProcessingFactory.AudioProcessing {
    override fun initialize(
        sampleRateHz: Int,
        numChannels: Int,
    ) = ducking.initialize(sampleRateHz, numChannels)

    override fun reset(newRate: Int) = ducking.reset(newRate)

    override fun process(
        numBands: Int,
        numFrames: Int,
        buffer: ByteBuffer,
    ) {
        buffer.order(ByteOrder.nativeOrder())
        stream()?.read(buffer, numFrames, numFrames * 100)
        ducking.process(numBands, numFrames, buffer)
    }
}
