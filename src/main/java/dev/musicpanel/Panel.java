package dev.musicpanel;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.util.List;

/** Renders the control panel: one embed + two rows of buttons. Button ids start with "mp:". */
final class Panel {

    private Panel() { }

    // ───────────────────────── embed ─────────────────────────

    static MessageEmbed embed(GuildMusic gm) {
        AudioTrack track = gm.player().getPlayingTrack();
        EmbedBuilder eb = new EmbedBuilder().setColor(0x5865F2);

        if (track == null) {
            eb.setTitle("🎵 Music Panel");
            eb.setDescription("Nothing is playing right now.\nUse **/play** with a YouTube link, a Spotify link, or a song name.");
        } else {
            AudioTrackInfo info = track.getInfo();
            eb.setTitle(gm.player().isPaused() ? "⏸️ Paused" : "🎶 Now Playing");

            String title = esc(cut(info.title, 120));
            String head = isUrl(info.uri) ? "**[" + title + "](" + info.uri + ")**" : "**" + title + "**";
            eb.setDescription(head + "\nby " + esc(cut(info.author, 80)));

            if (isUrl(info.artworkUrl)) eb.setThumbnail(info.artworkUrl);
            eb.addField("Progress", progress(track), false);

            String requester = track.getUserData(String.class);
            if (requester != null) eb.setFooter("Requested by " + requester);
        }

        eb.addField("Volume", volumeBar(gm.volume()) + " " + gm.volume() + "%", true);
        eb.addField("Loop", switch (gm.loop()) {
            case OFF -> "Off";
            case TRACK -> "🔂 Track";
            case QUEUE -> "🔁 Queue";
        }, true);
        eb.addField("In queue", gm.queueSize() + " track(s)", true);

        List<AudioTrack> next = gm.upcoming(3);
        if (!next.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < next.size(); i++) {
                sb.append('`').append(i + 1).append(".` ").append(esc(cut(next.get(i).getInfo().title, 60))).append('\n');
            }
            eb.addField("Up next", sb.toString().trim(), false);
        }

        if (gm.lastError() != null) {
            eb.addField("⚠️ Last error", cut(gm.lastError(), 300), false);
        }
        return eb.build();
    }

    static MessageEmbed stopped(String reason) {
        return new EmbedBuilder()
                .setColor(0x99AAB5)
                .setTitle("⏹️ Stopped")
                .setDescription(reason + "\nUse **/play** to start the music again.")
                .build();
    }

    // ───────────────────────── buttons ─────────────────────────

    static List<ActionRow> rows(GuildMusic gm) {
        boolean idle = gm.player().getPlayingTrack() == null;
        boolean paused = gm.player().isPaused();

        Button pause = paused
                ? Button.success("mp:pause", "Resume").withEmoji(Emoji.fromUnicode("▶️"))
                : Button.primary("mp:pause", "Pause").withEmoji(Emoji.fromUnicode("⏸️"));
        Button skip = Button.secondary("mp:skip", "Skip").withEmoji(Emoji.fromUnicode("⏭️"));
        Button stop = Button.danger("mp:stop", "Stop").withEmoji(Emoji.fromUnicode("⏹️"));

        GuildMusic.Loop loop = gm.loop();
        String loopLabel = "Loop: " + switch (loop) {
            case OFF -> "Off";
            case TRACK -> "Track";
            case QUEUE -> "Queue";
        };
        Button loopBtn = (loop == GuildMusic.Loop.OFF ? Button.secondary("mp:loop", loopLabel) : Button.success("mp:loop", loopLabel))
                .withEmoji(Emoji.fromUnicode("🔁"));
        Button shuffle = Button.secondary("mp:shuffle", "Shuffle").withEmoji(Emoji.fromUnicode("🔀"));

        Button volDown = Button.secondary("mp:voldown", "Vol -").withEmoji(Emoji.fromUnicode("🔉"));
        Button volLabel = Button.secondary("mp:vol", gm.volume() + "%").withEmoji(Emoji.fromUnicode("🔊")).asDisabled();
        Button volUp = Button.secondary("mp:volup", "Vol +").withEmoji(Emoji.fromUnicode("🔊"));
        Button queue = Button.secondary("mp:queue", "Queue").withEmoji(Emoji.fromUnicode("📜"));

        return List.of(
                ActionRow.of(pause.withDisabled(idle), skip.withDisabled(idle), stop, loopBtn, shuffle.withDisabled(gm.queueSize() < 2)),
                ActionRow.of(volDown, volLabel, volUp, queue));
    }

    // ───────────────────────── helpers ─────────────────────────

    static String esc(String s) {
        return MarkdownSanitizer.escape(s == null ? "" : s);
    }

    static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static boolean isUrl(String s) {
        return s != null && (s.startsWith("https://") || s.startsWith("http://")) && !s.contains(")") && !s.contains(" ");
    }

    private static String progress(AudioTrack track) {
        if (track.getInfo().isStream) return "🔴 LIVE";
        long position = track.getPosition();
        long duration = Math.max(1, track.getDuration());
        int slots = 14;
        int marker = (int) Math.min(slots - 1, position * slots / duration);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < slots; i++) bar.append(i == marker ? "🔘" : "▬");
        return bar + "\n`" + time(position) + " / " + time(duration) + "`";
    }

    private static String volumeBar(int volume) {
        int filled = Math.max(0, Math.min(10, volume / 10));
        return "▮".repeat(filled) + "▯".repeat(10 - filled);
    }

    private static String time(long millis) {
        long total = millis / 1000;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
