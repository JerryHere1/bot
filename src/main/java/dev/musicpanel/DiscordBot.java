package dev.musicpanel;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import moe.kyokobot.libdave.DaveFactory;
import moe.kyokobot.libdave.NativeDaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.configuration.file.FileConfiguration;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The Discord side: logs in, registers /play and /panel, and handles the panel's buttons.
 * The panel is posted in the text chat of the voice channel the user is sitting in.
 */
final class DiscordBot extends ListenerAdapter {

    /** Permission bits for the invite link: View Channel, Send Messages, Embed Links, Connect, Speak. */
    private static final long INVITE_PERMISSIONS = 3165184L;

    private final MusicPanelPlugin plugin;
    private volatile AudioService audio;
    private volatile JDA jda;
    private volatile boolean stopped;

    DiscordBot(MusicPanelPlugin plugin) {
        this.plugin = plugin;
    }

    // ───────────────────────── lifecycle ─────────────────────────

    void start() throws InterruptedException {
        FileConfiguration cfg = plugin.getConfig();
        checkDaveNatives();

        AudioService service = new AudioService(plugin);
        audio = service;

        DaveFactory dave = new NativeDaveFactory();

        JDA built = JDABuilder.createLight(cfg.getString("discord.token", ""))
                .enableIntents(GatewayIntent.GUILD_VOICE_STATES)
                .enableCache(CacheFlag.VOICE_STATE)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .setAudioModuleConfig(new AudioModuleConfig()
                        .withDaveSessionFactory(new LDJDADaveSessionFactory(dave)))
                .setActivity(Activity.listening(cfg.getString("discord.activity", "/play")))
                .addEventListeners(this)
                .build();

        built.awaitReady();
        if (stopped) {
            built.shutdownNow();
            return;
        }

        jda = built;
        service.attach(built);
        registerCommands(built, cfg.getString("discord.guild-id", ""));

        plugin.getLogger().info("Music bot online as " + built.getSelfUser().getName() + ".");
        plugin.getLogger().info("Invite link: https://discord.com/oauth2/authorize?client_id=" + built.getSelfUser().getId()
                + "&scope=bot%20applications.commands&permissions=" + INVITE_PERMISSIONS);
    }

    /**
     * Discord requires DAVE end-to-end encryption for voice. Its native library must load on this OS/CPU,
     * otherwise the bot connects, then gets kicked from voice (close code 4017). Fail early with a clear message.
     * Called by name because older libdave versions don't have ensureAvailable().
     */
    private void checkDaveNatives() {
        try {
            NativeDaveFactory.class.getMethod("ensureAvailable").invoke(null);
            plugin.getLogger().info("DAVE voice encryption library loaded.");
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            // older libdave: the library is loaded on the first voice connection instead
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("The DAVE voice-encryption library could not be loaded on this server ("
                    + System.getProperty("os.name") + " / " + System.getProperty("os.arch") + "): " + cause
                    + ". Supported: Linux x86-64, Linux aarch64, Windows x86-64.", cause);
        }
    }

    void shutdown() {
        stopped = true;
        AudioService service = audio;
        JDA current = jda;
        if (service != null) service.shutdown();   // leaves voice + marks panels as stopped while JDA is still up
        if (current != null) {
            current.shutdown();
            try {
                if (!current.awaitShutdown(5, TimeUnit.SECONDS)) current.shutdownNow();
            } catch (InterruptedException e) {
                current.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    boolean isOnline() {
        JDA current = jda;
        return current != null && current.getStatus() == JDA.Status.CONNECTED;
    }

    String botName() {
        JDA current = jda;
        return current == null ? "?" : current.getSelfUser().getName();
    }

    int activePlayers() {
        AudioService service = audio;
        return service == null ? 0 : service.activePlayers();
    }

    private void registerCommands(JDA j, String guildId) {
        List<SlashCommandData> commands = List.of(
                Commands.slash("play", "Play a YouTube / Spotify link, or search by name")
                        .addOption(OptionType.STRING, "query", "YouTube or Spotify link, or search words", true)
                        .setContexts(InteractionContextType.GUILD),
                Commands.slash("panel", "Post the music control panel in your voice channel's chat")
                        .setContexts(InteractionContextType.GUILD));

        if (!guildId.isBlank()) {
            Guild guild = j.getGuildById(guildId);
            if (guild != null) {
                guild.updateCommands().addCommands(commands).queue();
                return;
            }
            plugin.getLogger().warning("discord.guild-id " + guildId + " not found (is the bot invited there?) - registering commands globally.");
        }
        j.updateCommands().addCommands(commands).queue();
    }

    // ───────────────────────── slash commands ─────────────────────────

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        Guild guild = e.getGuild();
        Member member = e.getMember();
        AudioService service = audio;
        if (guild == null || member == null || service == null) return;

        switch (e.getName()) {
            case "play" -> handlePlay(e, guild, member, service);
            case "panel" -> handlePanel(e, guild, member, service);
            default -> { }
        }
    }

    private void handlePlay(SlashCommandInteractionEvent e, Guild guild, Member member, AudioService service) {
        GuildVoiceState voice = member.getVoiceState();
        if (voice == null || !voice.inAudioChannel() || voice.getChannel() == null) {
            e.reply("🔊 Join a voice channel first, then run `/play` again.").setEphemeral(true).queue();
            return;
        }
        AudioChannel vc = voice.getChannel();
        Member self = guild.getSelfMember();

        if (!self.hasPermission(vc, Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)) {
            e.reply("❌ I need **View Channel**, **Connect** and **Speak** in " + vc.getAsMention() + ".").setEphemeral(true).queue();
            return;
        }

        AudioChannelUnion botChannel = guild.getAudioManager().getConnectedChannel();
        if (botChannel != null && botChannel.getIdLong() != vc.getIdLong() && humans(botChannel) > 0) {
            e.reply("🎧 I'm already playing in " + botChannel.getAsMention() + ". Join that channel to add songs.").setEphemeral(true).queue();
            return;
        }

        GuildMessageChannel chat = panelChannel(vc, e);
        if (chat == null || !self.hasPermission(chat, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
            e.reply("❌ I need **Send Messages** and **Embed Links** in " + (chat == null ? "this channel" : chat.getAsMention()) + " to show the panel.")
                    .setEphemeral(true).queue();
            return;
        }

        String query = e.getOption("query", opt -> opt.getAsString());
        if (query == null || query.isBlank()) {
            e.reply("Type a link or a song name after `/play`.").setEphemeral(true).queue();
            return;
        }

        e.deferReply(true).queue();

        GuildMusic gm = service.get(guild);
        gm.connect(vc);
        service.load(gm, query, member.getEffectiveName(), message -> {
            e.getHook().editOriginal(message).queue();
            gm.ensurePanel(chat);
        });
    }

    private void handlePanel(SlashCommandInteractionEvent e, Guild guild, Member member, AudioService service) {
        GuildMusic gm = service.find(guild.getIdLong());
        GuildVoiceState voice = member.getVoiceState();
        AudioChannelUnion botChannel = guild.getAudioManager().getConnectedChannel();

        if (gm == null || botChannel == null || voice == null || voice.getChannel() == null
                || voice.getChannel().getIdLong() != botChannel.getIdLong()) {
            e.reply("Nothing is playing for you. Join the voice channel I'm in, or start music with `/play`.").setEphemeral(true).queue();
            return;
        }
        GuildMessageChannel chat = panelChannel(botChannel, e);
        if (chat == null) {
            e.reply("❌ I can't post messages here.").setEphemeral(true).queue();
            return;
        }
        gm.resendPanel(chat);
        e.reply("📻 Panel posted.").setEphemeral(true).queue();
    }

    /** The voice channel's own text chat; falls back to the channel the command was typed in. */
    private static GuildMessageChannel panelChannel(AudioChannel vc, SlashCommandInteractionEvent e) {
        if (vc instanceof GuildMessageChannel voiceChat) return voiceChat;
        return e.getChannel() instanceof GuildMessageChannel typedIn ? typedIn : null;
    }

    // ───────────────────────── panel buttons ─────────────────────────

    @Override
    public void onButtonInteraction(ButtonInteractionEvent e) {
        String id = e.getComponentId();
        Guild guild = e.getGuild();
        Member member = e.getMember();
        AudioService service = audio;
        if (!id.startsWith("mp:") || guild == null || member == null || service == null) return;

        GuildMusic gm = service.find(guild.getIdLong());
        if (gm == null) {
            e.reply("This panel is no longer active. Use `/play` to start again.").setEphemeral(true).queue();
            return;
        }

        // Only people sitting in the bot's voice channel may press the buttons.
        AudioChannelUnion botChannel = guild.getAudioManager().getConnectedChannel();
        GuildVoiceState voice = member.getVoiceState();
        if (botChannel == null || voice == null || voice.getChannel() == null
                || voice.getChannel().getIdLong() != botChannel.getIdLong()) {
            e.reply("🔊 Join " + (botChannel == null ? "my voice channel" : botChannel.getAsMention()) + " to use the controls.")
                    .setEphemeral(true).queue();
            return;
        }

        int step = plugin.getConfig().getInt("player.volume-step", 10);
        switch (id) {
            case "mp:pause" -> gm.togglePause();
            case "mp:skip" -> gm.skip();
            case "mp:loop" -> gm.cycleLoop();
            case "mp:shuffle" -> gm.shuffle();
            case "mp:voldown" -> gm.changeVolume(-step);
            case "mp:volup" -> gm.changeVolume(step);
            case "mp:stop" -> {
                e.editMessageEmbeds(Panel.stopped("Stopped by " + member.getEffectiveName() + ".")).setComponents(List.of()).queue();
                gm.destroy("Stopped by " + member.getEffectiveName() + ".");
                return;
            }
            case "mp:queue" -> {
                e.reply(queueText(gm)).setEphemeral(true).queue();
                return;
            }
            default -> {
                e.deferEdit().queue();
                return;
            }
        }

        // Answer the click by redrawing the panel that was clicked.
        e.editMessageEmbeds(Panel.embed(gm)).setComponents(Panel.rows(gm)).queue();
    }

    private static String queueText(GuildMusic gm) {
        List<AudioTrack> next = gm.upcoming(10);
        if (next.isEmpty()) return "📜 The queue is empty.";
        StringBuilder sb = new StringBuilder("📜 **Up next** (" + gm.queueSize() + " in queue)\n");
        for (int i = 0; i < next.size(); i++) {
            sb.append(i + 1).append(". ").append(Panel.esc(Panel.cut(next.get(i).getInfo().title, 70))).append('\n');
        }
        if (gm.queueSize() > next.size()) sb.append("...and ").append(gm.queueSize() - next.size()).append(" more");
        return sb.toString().trim();
    }

    // ───────────────────────── voice events ─────────────────────────

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent e) {
        AudioService service = audio;
        if (service == null) return;
        Guild guild = e.getGuild();
        GuildMusic gm = service.find(guild.getIdLong());
        if (gm == null) return;

        // The bot itself was kicked / disconnected.
        if (e.getMember().getIdLong() == guild.getSelfMember().getIdLong() && e.getChannelJoined() == null) {
            gm.destroy("I was disconnected from the voice channel.");
            return;
        }

        AudioChannelUnion botChannel = guild.getAudioManager().getConnectedChannel();
        if (botChannel != null) gm.onHumanCount(humans(botChannel));
    }

    private static int humans(AudioChannel channel) {
        int n = 0;
        for (Member m : channel.getMembers()) {
            if (!m.getUser().isBot()) n++;
        }
        return n;
    }
}
