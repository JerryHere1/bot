package dev.musicpanel;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import net.dv8tion.jda.api.audio.AudioSendHandler;

import java.nio.ByteBuffer;

/** Feeds Lavaplayer's Opus frames to JDA (one 20ms frame at a time). */
final class SendHandler implements AudioSendHandler {

    private final AudioPlayer player;
    private final ByteBuffer buffer = ByteBuffer.allocate(1024);
    private final MutableAudioFrame frame = new MutableAudioFrame();

    SendHandler(AudioPlayer player) {
        this.player = player;
        this.frame.setBuffer(buffer);
    }

    @Override
    public boolean canProvide() {
        return player.provide(frame);
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        buffer.flip();
        return buffer;
    }

    @Override
    public boolean isOpus() {
        return true;
    }
}
