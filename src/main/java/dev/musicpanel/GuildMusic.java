package dev.musicpanel;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.ErrorResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Everything about music playback in ONE Discord server: player, queue, loop mode and the control panel message. */
final class GuildMusic {

    enum Loop { OFF, TRACK, QUEUE }

    private final AudioService service;
    private final long guildId;
    private final AudioPlayer player;
    private final SendHandler sendHandler;

    private final Deque<AudioTrack> queue = new ArrayDeque<>();   // guarded by "this"
    private volatile Loop loop = Loop.OFF;
    private volatile String lastError;

    private volatile long panelChannelId;
    private volatile long panelMessageId;
    private volatile boolean panelPending;   // a panel message is being created right now

    private ScheduledFuture<?> idleTask;    // guarded by "this"
    private ScheduledFuture<?> emptyTask;   // guarded by "this"
    private boolean destroyed;              // guarded by "this"

    GuildMusic(AudioService service, long guildId) {
        this.service = service;
        this.guildId = guildId;
        this.player = service.playerManager().createPlayer();
        this.sendHandler = new SendHandler(player);

        int def = service.cfg().getInt("player.default-volume", 50);
        player.setVolume(Math.max(0, Math.min(maxVolume(), def)));

        player.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackStart(AudioPlayer p, AudioTrack track) {
                cancelIdle();
                refreshPanel();
            }

            @Override
            public void onTrackEnd(AudioPlayer p, AudioTrack track, AudioTrackEndReason reason) {
                if (!reason.mayStartNext) return;
                synchronized (GuildMusic.this) {
                    if (destroyed) return;
                    if (reason == AudioTrackEndReason.FINISHED) {
                        if (loop == Loop.TRACK) {
                            p.startTrack(copy(track), false);
                            return;
                        }
                        if (loop == Loop.QUEUE) queue.offer(copy(track));
                    }
                    advance();
                }
            }

            @Override
            public void onTrackException(AudioPlayer p, AudioTrack track, FriendlyException ex) {
                service.log().warning("Playback error for '" + track.getInfo().title + "': " + ex.getMessage());
                lastError = "Couldn't play \"" + track.getInfo().title + "\": " + ex.getMessage();
                refreshPanel();
            }

            @Override
            public void onTrackStuck(AudioPlayer p, AudioTrack track, long thresholdMs) {
                service.log().warning("Track stuck, skipping: " + track.getInfo().title);
                lastError = "\"" + track.getInfo().title + "\" got stuck and was skipped.";
                synchronized (GuildMusic.this) {
                    advance();
                }
            }
        });
    }

    // ───────────────────────── state for the panel ─────────────────────────

    AudioPlayer player() { return player; }
    Loop loop() { return loop; }
    String lastError() { return lastError; }
    int volume() { return player.getVolume(); }
    int maxVolume() { return service.cfg().getInt("player.max-volume", 150); }

    synchronized int queueSize() { return queue.size(); }

    synchronized List<AudioTrack> upcoming(int n) {
        List<AudioTrack> out = new ArrayList<>();
        for (AudioTrack t : queue) {
            if (out.size() >= n) break;
            out.add(t);
        }
        return out;
    }

    // ───────────────────────── voice connection ─────────────────────────

    void connect(AudioChannel vc) {
        Guild guild = guild();
        if (guild == null) return;
        AudioManager am = guild.getAudioManager();
        am.setSendingHandler(sendHandler);
        am.setSelfDeafened(true);
        am.openAudioConnection(vc);
    }

    private Guild guild() {
        JDA jda = service.jda();
        return jda == null ? null : jda.getGuildById(guildId);
    }

    // ───────────────────────── queue + controls ─────────────────────────

    /** @return how many tracks were actually added */
    synchronized int enqueueAll(List<AudioTrack> tracks, String requester) {
        int max = service.cfg().getInt("player.max-queue-size", 200);
        int added = 0;
        lastError = null;
        for (AudioTrack t : tracks) {
            if (queue.size() >= max) break;
            t.setUserData(requester);
            if (!player.startTrack(t, true)) {   // true = don't interrupt what's playing
                queue.offer(t);
            }
            added++;
        }
        if (added > 0) cancelIdle();
        return added;
    }

    void togglePause() {
        player.setPaused(!player.isPaused());
    }

    void skip() {
        synchronized (this) {
            lastError = null;
            AudioTrack current = player.getPlayingTrack();
            if (current != null && loop == Loop.QUEUE) queue.offer(copy(current));
            player.setPaused(false);
            AudioTrack next = queue.poll();
            if (next != null) {
                player.startTrack(next, false);
            } else {
                player.stopTrack();
                scheduleIdle();
            }
        }
        refreshPanel();
    }

    Loop cycleLoop() {
        loop = Loop.values()[(loop.ordinal() + 1) % Loop.values().length];
        return loop;
    }

    synchronized void shuffle() {
        if (queue.size() < 2) return;
        List<AudioTrack> all = new ArrayList<>(queue);
        Collections.shuffle(all);
        queue.clear();
        queue.addAll(all);
    }

    int changeVolume(int delta) {
        int v = Math.max(0, Math.min(maxVolume(), player.getVolume() + delta));
        player.setVolume(v);
        return v;
    }

    /** Called with the lock held (or from the player thread) when the current track is over. */
    private synchronized void advance() {
        AudioTrack next = queue.poll();
        if (next != null) {
            player.startTrack(next, false);
        } else {
            refreshPanel();
            scheduleIdle();
        }
    }

    private static AudioTrack copy(AudioTrack track) {
        AudioTrack clone = track.makeClone();
        clone.setUserData(track.getUserData());
        return clone;
    }

    // ───────────────────────── idle / empty-channel timers ─────────────────────────

    private synchronized void scheduleIdle() {
        cancelIdle();
        int seconds = service.cfg().getInt("player.idle-leave-seconds", 180);
        if (seconds <= 0 || destroyed) return;
        idleTask = service.scheduler().schedule(() -> destroy("The queue finished."), seconds, TimeUnit.SECONDS);
    }

    private synchronized void cancelIdle() {
        if (idleTask != null) {
            idleTask.cancel(false);
            idleTask = null;
        }
    }

    /** Called whenever someone joins/leaves the bot's voice channel. */
    synchronized void onHumanCount(int humans) {
        if (humans > 0) {
            if (emptyTask != null) {
                emptyTask.cancel(false);
                emptyTask = null;
            }
            return;
        }
        int seconds = service.cfg().getInt("player.empty-leave-seconds", 30);
        if (seconds <= 0 || destroyed || emptyTask != null) return;
        emptyTask = service.scheduler().schedule(() -> destroy("Everyone left the voice channel."), seconds, TimeUnit.SECONDS);
    }

    // ───────────────────────── teardown ─────────────────────────

    void destroy(String reason) {
        synchronized (this) {
            if (destroyed) return;
            destroyed = true;
            queue.clear();
            if (idleTask != null) idleTask.cancel(false);
            if (emptyTask != null) emptyTask.cancel(false);
        }

        player.stopTrack();

        Guild guild = guild();
        if (guild != null) {
            AudioManager am = guild.getAudioManager();
            am.closeAudioConnection();
            am.setSendingHandler(null);
        }

        showStopped(reason);
        player.destroy();
        service.remove(guildId);
    }

    // ───────────────────────── the panel message ─────────────────────────

    /** Makes sure a live panel exists in {@code chat} (re-using the current one when possible). */
    void ensurePanel(GuildMessageChannel chat) {
        if (panelChannelId == chat.getIdLong()) {
            if (panelPending) return;
            if (panelMessageId != 0) {
                refreshPanel();
                return;
            }
        }
        sendNewPanel(chat);
    }

    /** Always posts a fresh panel (and deletes the old one). Used by /panel. */
    void resendPanel(GuildMessageChannel chat) {
        sendNewPanel(chat);
    }

    private void sendNewPanel(GuildMessageChannel chat) {
        final long oldChannel = panelChannelId;
        final long oldMessage = panelMessageId;
        // Claim the slot right away so two quick /play commands don't create two panels.
        panelChannelId = chat.getIdLong();
        panelMessageId = 0;
        panelPending = true;

        chat.sendMessageEmbeds(Panel.embed(this)).setComponents(Panel.rows(this)).queue(
                message -> {
                    panelMessageId = message.getIdLong();
                    panelPending = false;
                    if (oldMessage != 0) deleteMessage(oldChannel, oldMessage);
                },
                error -> {
                    panelPending = false;
                    service.log().warning("Could not post the music panel: " + error.getMessage());
                });
    }

    void refreshPanel() {
        long messageId = panelMessageId;
        if (messageId == 0) return;
        Guild guild = guild();
        if (guild == null) return;
        GuildMessageChannel chat = guild.getChannelById(GuildMessageChannel.class, panelChannelId);
        if (chat == null) return;

        chat.editMessageEmbedsById(messageId, Panel.embed(this)).setComponents(Panel.rows(this)).queue(
                null,
                error -> {
                    if (error instanceof ErrorResponseException ex && ex.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) {
                        panelMessageId = 0;   // somebody deleted the panel; /play will post a new one
                    }
                });
    }

    /** Periodic refresh so the progress bar moves. */
    void tickPanel() {
        if (player.getPlayingTrack() != null && !player.isPaused()) refreshPanel();
    }

    private void showStopped(String reason) {
        long messageId = panelMessageId;
        if (messageId == 0) return;
        Guild guild = guild();
        if (guild == null) return;
        GuildMessageChannel chat = guild.getChannelById(GuildMessageChannel.class, panelChannelId);
        if (chat == null) return;
        chat.editMessageEmbedsById(messageId, Panel.stopped(reason)).setComponents(List.of()).queue(null, e -> { });
    }

    private void deleteMessage(long channelId, long messageId) {
        Guild guild = guild();
        if (guild == null) return;
        GuildMessageChannel chat = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (chat != null) chat.deleteMessageById(messageId).queue(null, e -> { });
    }
}
