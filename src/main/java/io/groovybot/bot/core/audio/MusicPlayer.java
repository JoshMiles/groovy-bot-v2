package io.groovybot.bot.core.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import io.groovybot.bot.GroovyBot;
import io.groovybot.bot.core.command.CommandEvent;
import io.groovybot.bot.core.command.permission.Permissions;
import io.groovybot.bot.core.command.permission.UserPermissions;
import io.groovybot.bot.core.entity.EntityProvider;
import io.groovybot.bot.util.EmbedUtil;
import io.groovybot.bot.util.SafeMessage;
import io.groovybot.bot.util.YoutubeUtil;
import lavalink.client.player.IPlayer;
import lavalink.client.player.LavaplayerPlayerWrapper;
import lombok.Getter;
import lombok.extern.log4j.Log4j;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.*;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Log4j
public class MusicPlayer extends Player {

    @Getter
    private final Guild guild;
    private final TextChannel channel;
    @Getter
    private final AudioPlayerManager audioPlayerManager;

    protected MusicPlayer(Guild guild, TextChannel channel, YoutubeUtil youtubeClient) {
        super(youtubeClient);
        LavalinkManager lavalinkManager = GroovyBot.getInstance().getLavalinkManager();
        this.guild = guild;
        this.channel = channel;
        instanciatePlayer(lavalinkManager.getLavalink().getLink(guild));
        getPlayer().addListener(getScheduler());
        audioPlayerManager = lavalinkManager.getAudioPlayerManager();
    }

    public void connect(VoiceChannel channel) {
        link.connect(channel);
        Objects.requireNonNull(link.getGuild()).getAudioManager().setSelfDeafened(true);
    }

    public boolean checkConnect(CommandEvent event) {
        if (!event.getGuild().getSelfMember().hasPermission(event.getMember().getVoiceState().getChannel(), Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)) {
            SafeMessage.sendMessage(event.getChannel(), EmbedUtil.error(event.translate("phrases.nopermission.title"), event.translate("phrases.join.nopermission.description")));
            return false;
        }
        final GuildVoiceState voiceState = event.getGuild().getSelfMember().getVoiceState();
        if (voiceState.inVoiceChannel() && voiceState.getChannel().getMembers().size() != 1 && !Permissions.djMode().isCovered(event.getPermissions(), event)) {
            SafeMessage.sendMessage(event.getChannel(), EmbedUtil.error(event.translate("phrases.djrequired.title"), event.translate("phrases.djrequired.description")));
            return false;
        }
        return true;
    }

    public void leave() {
        trackQueue.clear();
        if (!this.getGuild().getId().equals("403882830225997825"))
            link.disconnect();
    }

    @Override
    public void onEnd(boolean announce) {
        if (announce)
            SafeMessage.sendMessage(channel, EmbedUtil.success("The queue ended!", "Why not queue more songs?"));
        if (!this.getGuild().getId().equals("403882830225997825"))
            link.disconnect();
        stop();
    }

    @Override
    public Message announceAutoplay(Player player) {
        return SafeMessage.sendMessageBlocking(channel, EmbedUtil.info("Searching video", "Searching new autoplay video"));
    }

    @Override
    public void announceRequeue(AudioTrack track) {
        SafeMessage.sendMessage(channel, EmbedUtil.success("An error occurred while queueing song", "An unexpected error occurred while queueing song, trying to requeue now!"));
    }

    @Override
    protected void save() {
        GroovyBot.getInstance().getMusicPlayerManager().update(guild, this);
    }

    @Override
    public void announceSong(AudioPlayer audioPlayer, AudioTrack track) {
        if (EntityProvider.getGuild(guild.getIdLong()).isAnnounceSongs())
            channel.sendMessage(EmbedUtil.play("Now Playing", String.format("%s (%s)", track.getInfo().title, track.getInfo().author)).build()).queue();
    }


    @Override
    public IPlayer getPlayer() {
        this.player = this.player == null ? new LavaplayerPlayerWrapper(getAudioPlayerManager().createPlayer()) : this.player;
        return this.player;
    }

    public void queueSongs(CommandEvent event, boolean force, boolean playtop) {
        UserPermissions userPermissions = EntityProvider.getUser(event.getAuthor().getIdLong()).getPermissions();
        Permissions tierTwo = Permissions.tierTwo();
        if (trackQueue.size() >= 25 && !tierTwo.isCovered(userPermissions, event)) {
            SafeMessage.sendMessage(event.getChannel(), EmbedUtil.error(event.translate("phrases.fullqueue.title"), event.translate("phrases.fullqueue.description")));
            return;
        }
        String keyword = event.getArguments();
        boolean isUrl = true;

        if (!keyword.startsWith("http://") && !keyword.startsWith("https://")) {
            keyword = "ytsearch: " + keyword;
            isUrl = false;
        }

        Message infoMessage = SafeMessage.sendMessageBlocking(event.getChannel(), EmbedUtil.info(event.translate("phrases.searching.title"), String.format(event.translate("phrases.searching.description"), event.getArguments())));

        final boolean isURL = isUrl;
        getAudioPlayerManager().loadItem(keyword, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack audioTrack) {
                if (!checkSong(audioTrack))
                    return;
                queueTrack(audioTrack, force, playtop);
                infoMessage.editMessage(EmbedUtil.success(event.translate("phrases.searching.trackloaded.title"), String.format(event.translate("phrases.searching.trackloaded.description"), audioTrack.getInfo().title)).build()).queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist audioPlaylist) {
                List<AudioTrack> tracks = audioPlaylist.getTracks();
                if (!tierTwo.isCovered(userPermissions, event))
                    tracks = tracks.stream()
                            .limit(25 - getQueueSize())
                            .filter(track -> track.getDuration() < 3600000)
                            .collect(Collectors.toList());

                if (tracks.isEmpty()) {
                    SafeMessage.sendMessage(event.getChannel(), EmbedUtil.error(event));
                    return;
                }

                if (isURL) {
                    queueTracks(tracks.toArray(new AudioTrack[0]));
                    infoMessage.editMessage(EmbedUtil.success(event.translate("phrases.searching.playlistloaded.title"), String.format(event.translate("phrases.searching.playlistloaded.description"), audioPlaylist.getName())).build()).queue();
                    return;
                }
                final AudioTrack track = tracks.get(0);
                if (!checkSong(track))
                    return;
                queueTrack(track, force, playtop);
                infoMessage.editMessage(EmbedUtil.success(event.translate("phrases.searching.trackloaded.title"), String.format(event.translate("phrases.searching.trackloaded.description"), track.getInfo().title)).build()).queue();
            }

            @Override
            public void noMatches() {
                infoMessage.editMessage(EmbedUtil.error(event.translate("phrases.searching.nomatches.title"), event.translate("phrases.searching.nomatches.description")).build()).queue();
            }

            @Override
            public void loadFailed(FriendlyException e) {
                if (e.getMessage().toLowerCase().contains("Unknown file format")) {
                    infoMessage.editMessage(EmbedUtil.error(event.translate("phrases.searching.unknownformat.tile"), event.translate("phrases.searching.unknownformat.description")).build()).queue();
                    return;
                }
                if (e.getMessage().toLowerCase().contains("The playlist is private")) {
                    infoMessage.editMessage(EmbedUtil.error(event.translate("phrases.searching.private.tile"), event.translate("phrases.searching.private.description")).build()).queue();
                    return;
                }
                infoMessage.editMessage(EmbedUtil.error(event).build()).queue();
                log.error("[PlayCommand] Error while loading track!", e);
            }

            private boolean checkSong(AudioTrack track) {
                if (track.getDuration() > 3600000 && !Permissions.tierTwo().isCovered(userPermissions, event)) {
                    SafeMessage.sendMessage(event.getChannel(), EmbedUtil.error(event.translate("phrases.toolongsong.title"), event.translate("phrases.toolongsong.description")));
                    if (trackQueue.isEmpty()) {
                        if (getGuild().getId().equals("403882830225997825"))
                            link.disconnect();
                        System.out.println("Disconnect 3");
                    }
                    return false;
                }
                return true;
            }
        });
    }

    public void update() {
        this.clearQueue();
        getScheduler().setShuffle(false);
        getScheduler().setQueueRepeating(false);
        getScheduler().setRepeating(false);
        setVolume(100);
        if (isPaused())
            resume();
        getAudioPlayerManager().loadItem("https://cdn.groovybot.gq/sounds/update.mp3", new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                queueTrack(track, true, false);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {

            }

            @Override
            public void noMatches() {

            }

            @Override
            public void loadFailed(FriendlyException exception) {

            }
        });
    }
}
