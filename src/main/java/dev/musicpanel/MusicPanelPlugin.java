package dev.musicpanel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Paper plugin that hosts a Discord music bot. The bot lives and dies with the plugin.
 */
public final class MusicPanelPlugin extends JavaPlugin implements TabExecutor {

    private volatile DiscordBot bot;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PluginCommand cmd = getCommand("musicpanel");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        startBot();
    }

    @Override
    public void onDisable() {
        stopBot();
    }

    private void startBot() {
        String token = getConfig().getString("discord.token", "");
        if (token.isBlank() || token.startsWith("PUT_")) {
            getLogger().warning("No Discord bot token set in plugins/DiscordMusicPanel/config.yml - the bot will not start.");
            return;
        }

        DiscordBot newBot = new DiscordBot(this);
        bot = newBot;

        // Logging in blocks for a moment, so keep it off the main server thread.
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                newBot.start();
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Failed to start the Discord bot", t);
            }
        });
    }

    private void stopBot() {
        DiscordBot current = bot;
        bot = null;
        if (current != null) {
            current.shutdown();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                DiscordBot current = bot;
                if (current == null || !current.isOnline()) {
                    sender.sendMessage(Component.text("Music bot is offline.", NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text(
                            "Music bot online as " + current.botName() + " - " + current.activePlayers() + " active player(s).",
                            NamedTextColor.GREEN));
                }
            }
            case "reload" -> {
                reloadConfig();
                stopBot();
                startBot();
                sender.sendMessage(Component.text("Config reloaded, restarting the music bot...", NamedTextColor.YELLOW));
            }
            case "stop" -> {
                stopBot();
                sender.sendMessage(Component.text("Music bot stopped. Use /musicpanel reload to start it again.", NamedTextColor.YELLOW));
            }
            default -> sender.sendMessage(Component.text("Usage: /musicpanel <status|reload|stop>", NamedTextColor.GRAY));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("status", "reload", "stop") : List.of();
    }
}
