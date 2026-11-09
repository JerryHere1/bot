package dev.musicpanel;

import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.NonAllocatingAudioFrameBuffer;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSourceOptions;
import dev.lavalink.youtube.clients.skeleton.Client;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Owns the Lavaplayer manager (with YouTube + Spotify registered) and one {@link GuildMusic} per Discord server.
 * Only YouTube and Spotify sources are registered on purpose: no generic HTTP or local-file source,
 * so Discord users can't make your Minecraft host fetch arbitrary URLs or read files.
 */
final class AudioService {

    private final MusicPanelPlugin plugin;
    private final Logger log;
    private final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
    private final Map<Long, GuildMusic> guilds = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DiscordMusicPanel-Scheduler");
        t.setDaemon(true);
        return t;
    });
    private volatile JDA jda;

    AudioService(MusicPanelPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();

        playerManager.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);
        registerYoutube(plugin.getConfig());
        registerSpotify(plugin.getConfig());
    }

    // ───────────────────────── sources ─────────────────────────

    private void registerYoutube(FileConfiguration cfg) {
        String backend = cfg.getString("youtube.backend", "youtube-source").toLowerCase();

        if (backend.equals("yt-dlp")) {
            String path = cfg.getString("youtube.yt-dlp-path", "yt-dlp");
            try {
                // Loaded by name: optional backend, so it can never break compilation.
                Class<?> type = Class.forName("com.github.topi314.lavasrc.ytdlp.YtdlpAudioSourceManager");
                playerManager.registerSourceManager((AudioSourceManager) type.getConstructor(String.class).newInstance(path));
            } catch (ReflectiveOperationException | LinkageError e) {
                throw new IllegalStateException("Could not set up the yt-dlp backend (is the LavaSrc version too old?): " + e, e);
            }
            log.info("YouTube backend: yt-dlp (" + path + ")");
            return;
        }

        YoutubeSourceOptions options = new YoutubeSourceOptions();

        String cipherUrl = cfg.getString("youtube.remote-cipher.url", "https://cipher.kikkia.dev/").trim();
        if (!cipherUrl.isEmpty()) {
            String cipherPassword = cfg.getString("youtube.remote-cipher.password", "").trim();
            String cipherAgent = cfg.getString("youtube.remote-cipher.user-agent", "DiscordMusicPanel").trim();
            options.setRemoteCipher(
                    cipherUrl,
                    cipherPassword.isEmpty() ? null : cipherPassword,
                    cipherAgent.isEmpty() ? null : cipherAgent);
            log.info("YouTube: using remote cipher server " + cipherUrl);
        }

        List<Client> clients = new ArrayList<>();
        for (String name : cfg.getStringList("youtube.clients")) {
            Client c = createClient(name.trim());
            if (c != null) clients.add(c);
        }
        if (clients.isEmpty()) {
            throw new IllegalStateException("youtube.clients in config.yml produced no usable YouTube clients.");
        }

        YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(options, clients.toArray(new Client[0]));

        if (cfg.getBoolean("youtube.oauth.enabled", false)) {
            String refresh = cfg.getString("youtube.oauth.refresh-token", "");
            youtube.useOauth2(refresh.isBlank() ? null : refresh, false);
            log.info("YouTube: OAuth enabled" + (refresh.isBlank() ? " - watch the console for the login code." : "."));
        }

        playerManager.registerSourceManager(youtube);
        log.info("YouTube backend: youtube-source with clients " + cfg.getStringList("youtube.clients"));
    }

    /** Creates a youtube-source client by class name; prefers the "WithThumbnail" variant (has cover art). */
    private Client createClient(String name) {
        for (String candidate : new String[]{name + "WithThumbnail", name}) {
            try {
                Class<?> type = Class.forName("dev.lavalink.youtube.clients." + candidate);
                return (Client) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | ClassCastException ignored) {
                // try next candidate
            }
        }
        log.warning("Unknown youtube-source client '" + name + "' (skipped). Check youtube.clients in config.yml.");
        return null;
    }

    private void registerSpotify(FileConfiguration cfg) {
        if (!cfg.getBoolean("spotify.enabled", false)) return;

        String id = cfg.getString("spotify.client-id", "");
        String secret = cfg.getString("spotify.client-secret", "");
        if (id.isBlank() || secret.isBlank()) {
            log.warning("spotify.enabled is true but client-id / client-secret are empty - Spotify links are disabled.");
            return;
        }

        // Audio for Spotify tracks is found on YouTube (ISRC first, then "title artist").
        String[] providers = {"ytsearch:\"%ISRC%\"", "ytsearch:%QUERY%"};
        SpotifySourceManager spotify = new SpotifySourceManager(
                providers, id, secret, cfg.getString("spotify.country-code", "US"), playerManager);
        playerManager.registerSourceManager(spotify);
        log.info("Spotify links enabled (audio is mirrored from YouTube).");
    }

    // ───────────────────────── lifecycle ─────────────────────────

    void attach(JDA jda) {
        this.jda = jda;
        int every = plugin.getConfig().getInt("player.panel-refresh-seconds", 15);
        if (every > 0) {
            scheduler.scheduleAtFixedRate(() -> {
                for (GuildMusic gm : guilds.values()) {
                    try {
                        gm.tickPanel();
                    } catch (Exception ignored) {
                        // never let one guild kill the timer
                    }
                }
            }, every, every, TimeUnit.SECONDS);
        }
    }

    void shutdown() {
        for (GuildMusic gm : new ArrayList<>(guilds.values())) {
            try {
                gm.destroy("The music bot is shutting down.");
            } catch (Exception ignored) {
                // best effort
            }
        }
        guilds.clear();
        scheduler.shutdownNow();
        playerManager.shutdown();
    }

    // ───────────────────────── guild players ─────────────────────────

    GuildMusic get(Guild guild) {
        return guilds.computeIfAbsent(guild.getIdLong(), id -> new GuildMusic(this, id));
    }

    GuildMusic find(long guildId) {
        return guilds.get(guildId);
    }

    void remove(long guildId) {
        guilds.remove(guildId);
    }

    int activePlayers() {
        return guilds.size();
    }

    AudioPlayerManager playerManager() {
        return playerManager;
    }

    ScheduledExecutorService scheduler() {
        return scheduler;
    }

    JDA jda() {
        return jda;
    }

    FileConfiguration cfg() {
        return plugin.getConfig();
    }

    Logger log() {
        return log;
    }

    // ───────────────────────── loading ─────────────────────────

    /** Resolves a link or search text and queues the result. {@code reply} receives a short status message. */
    void load(GuildMusic gm, String query, String requester, Consumer<String> reply) {
        String trimmed = query.trim();
        String identifier = trimmed.matches("(?i)^https?://\\S+$") ? trimmed : "ytsearch:" + trimmed;

        playerManager.loadItemOrdered(gm, identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                reply.accept(queue(gm, List.of(track), requester, null));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                List<AudioTrack> tracks = playlist.getTracks();
                if (tracks.isEmpty()) {
                    noMatches();
                    return;
                }
                if (playlist.isSearchResult()) {
                    reply.accept(queue(gm, List.of(tracks.get(0)), requester, null));
                    return;
                }
                int limit = Math.min(tracks.size(), cfg().getInt("player.max-playlist-tracks", 100));
                reply.accept(queue(gm, tracks.subList(0, limit), requester, playlist.getName()));
            }

            @Override
            public void noMatches() {
                reply.accept("❌ I couldn't find anything for that.");
            }

            @Override
            public void loadFailed(FriendlyException e) {
                log.warning("Load failed for '" + identifier + "': " + e.getMessage());
                reply.accept("❌ Couldn't load that: " + Panel.cut(String.valueOf(e.getMessage()), 180));
            }
        });
    }

    private String queue(GuildMusic gm, List<AudioTrack> tracks, String requester, String playlistName) {
        int added = gm.enqueueAll(tracks, requester);
        if (added == 0) return "❌ The queue is full.";
        if (playlistName != null) {
            return "✅ Queued **" + added + "** tracks from **" + Panel.esc(playlistName) + "**.";
        }
        return "✅ Queued **" + Panel.esc(tracks.get(0).getInfo().title) + "**.";
    }
}
