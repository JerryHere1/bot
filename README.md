# DiscordMusicPanel

A Paper **1.21.11** plugin that runs a **Discord music bot** inside your Minecraft server.
Play YouTube and Spotify links (or search by name) and control everything from a **button panel**
that appears in the text chat of the voice channel.

```
Panel:   [⏸ Pause] [⏭ Skip] [⏹ Stop] [🔁 Loop: Off] [🔀 Shuffle]
         [🔉 Vol -] [🔊 50%] [🔊 Vol +] [📜 Queue]
```

## How it works

1. Someone joins a Discord voice channel and types `/play <link or song name>`.
2. The bot joins that voice channel and posts the **panel** in that voice channel's chat.
3. Anyone sitting in the same voice channel can use the buttons. The progress bar updates by itself.

Commands: `/play <query>`, `/panel` (re-post the panel). In Minecraft: `/musicpanel <status|reload|stop>` (permission `musicpanel.admin`).

## Requirements

* Paper 1.21.11, **Java 21+**
* Linux x64 / Linux arm64 / Windows x64 host (the DAVE voice-encryption natives only exist for these as configured in `pom.xml`)
* A Discord bot token (https://discord.com/developers/applications → New Application → Bot → Reset Token). **No privileged intents needed.**
* To build: JDK 21 + Maven

## Build

**No Maven/Java on your PC? Use GitHub (what the included workflow is for):**
push this project to a GitHub repo → **Actions** tab → *Build plugin* → open the finished run →
download **DiscordMusicPanel-jar** at the bottom → unzip → `DiscordMusicPanel-1.0.0.jar`.

Or locally (JDK 21 + Maven):

```
mvn clean package
```

Copy `DiscordMusicPanel-1.0.0.jar` into your server's `plugins/` folder and start the server once.
Then edit `plugins/DiscordMusicPanel/config.yml`:

1. Paste your bot token under `discord.token`.
2. (Recommended) set `discord.guild-id` to your Discord server ID so slash commands show up instantly.
3. Run `/musicpanel reload` (or restart). The console prints an **invite link**; open it to add the bot.

The bot needs **View Channel, Connect, Speak, Send Messages, Embed Links** (the invite link already asks for these).

## Spotify

Create a free app at https://developer.spotify.com/dashboard, copy the Client ID / Secret into `config.yml`
under `spotify:` and set `enabled: true`. Spotify doesn't give out audio: the bot reads title/artist/cover from
Spotify and finds the matching audio on YouTube. Albums and playlists are supported.

## YouTube

YouTube keeps changing how it serves audio, and this is the most fragile part of any music bot. This plugin is set up
for the best odds out of the box:

* It is built against a **newer `youtube-source` commit** (pinned in `pom.xml` and `.github/workflows/build.yml`)
  instead of the old 1.18.2 release, which can't play many videos since YouTube's Sept 2026 format change.
* It uses the free public **remote cipher server** `https://cipher.kikkia.dev/` (`youtube.remote-cipher` in `config.yml`)
  so YouTube's scrambled stream URLs are unscrambled for you.
* Several YouTube "clients" are tried in order (`youtube.clients`), so if one is blocked the next one is used.

The console prints `YouTube backend: youtube-source with clients [...]` and `YouTube: using remote cipher server ...`
when it starts. If songs still fail with "All clients failed" / "sign in to confirm you're not a bot":

1. **Most reliable:** install `yt-dlp` on the host and set `youtube.backend: "yt-dlp"` in `config.yml`, then
   `/musicpanel reload`. yt-dlp is updated within days of YouTube changes (it also wants a JavaScript runtime such as Deno -
   see its docs). Keep it updated (`yt-dlp -U`).
2. Hosting providers' IP ranges are often flagged by YouTube. Enabling `youtube.oauth` with a **throwaway** Google
   account helps a lot (it can get banned, never use your main account).
3. To move to a newer youtube-source later, change the commit hash in `pom.xml` (`youtube.source.version`, keep `-SNAPSHOT`)
   **and** in `build.yml` (`YT_COMMIT`).

## Notes & troubleshooting

* **Discord requires DAVE voice encryption** (since March 2026). It is wired in via `libdave-jvm`. Voice error `4017` /
  "close code 4017" or `UnsatisfiedLinkError` means the DAVE natives for your OS are missing. Check the `natives-*`
  dependencies in `pom.xml`, or bump `libdave.version` / `libdave.natives.version`. The console prints `DAVE voice encryption library loaded.` when the native library works on your server.
* **Don't use `/reload` or PlugMan** on this plugin. Native libraries can't be loaded twice by different class loaders.
  Use `/musicpanel reload` or restart the server.
* If the build can't resolve a dependency, check the version numbers at the top of `pom.xml` (all pinned there).
* `NoSuchMethodError` / `LinkageError` from a bundled library: add a `<relocation>` for it in the shade config.
* Only YouTube and Spotify are enabled (no generic URL / local file source) so Discord users can't make your
  Minecraft host fetch arbitrary addresses.
* Streaming from YouTube/Spotify may break their terms of service. Fine for a private server; think twice before running it publicly.

## Project layout

```
src/main/java/dev/musicpanel/
  MusicPanelPlugin  - Paper plugin, /musicpanel command, starts/stops the bot
  DiscordBot        - JDA login (+DAVE), slash commands, button + voice listeners
  AudioService      - Lavaplayer, YouTube/Spotify sources, track loading
  GuildMusic        - per-server queue, loop, volume, timers, panel message
  Panel             - embed + button layout
  SendHandler       - Lavaplayer -> JDA audio bridge
```
