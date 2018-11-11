package com.magitechserver.magibridge;

import com.magitechserver.magibridge.util.*;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.key.Keys;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.profile.GameProfileManager;
import org.spongepowered.api.service.whitelist.WhitelistService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.service.user.UserStorageService;


import io.github.nucleuspowered.nucleus.api.exceptions.NicknameException;
import io.github.nucleuspowered.nucleus.api.service.NucleusNicknameService;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Created by Frani on 05/07/2017.
 */
public class DiscordHandler {

    public static void sendMessageToChannel(String channel, String message) {
        if (!isValidChannel(channel)) return;
        message = translateEmojis(message, MagiBridge.jda.getTextChannelById(channel).getGuild());
        List<String> usersMentioned = new ArrayList<>();
        Arrays.stream(message.split(" ")).filter(word ->
                word.startsWith("@")).forEach(mention ->
                usersMentioned.add(mention.substring(1)));

        if (!usersMentioned.isEmpty()) {
            for (String user : usersMentioned) {
                List<User> users = MagiBridge.jda.getUsersByName(user, true);
                if (!users.isEmpty()) {
                    message = message.replaceAll("@" + user, "<@" + users.get(0).getId() + ">");
                }
            }
        }
        MagiBridge.jda.getTextChannelById(channel).sendMessage(message.replaceAll("&([0-9a-fA-FlLkKrR])", "")).queue();
    }

    public static void sendMessageToChannel(String channel, String message, long deleteTime) {
        if (!isValidChannel(channel)) return;
        message = translateEmojis(message, MagiBridge.jda.getTextChannelById(channel).getGuild());
        MagiBridge.jda.getTextChannelById(channel).sendMessage(message.replaceAll("&([0-9a-fA-FlLkKrR])", ""))
                .queue(m -> m.delete().queueAfter(deleteTime, TimeUnit.SECONDS));
    }

    private static boolean isValidChannel(String channel) {
        if (MagiBridge.jda == null) return false;
        if (MagiBridge.jda.getStatus() != JDA.Status.CONNECTED) return false;
        if (MagiBridge.jda.getTextChannelById(channel) == null) {
            MagiBridge.getLogger().error("The channel " + channel + " defined in the config isn't a valid Discord Channel ID!");
            MagiBridge.getLogger().error("Replace it with a valid one then reload the plugin!");
            return false;
        }
        return true;
    }

    public static void sendMessageToDiscord(
            String channel, FormatType format, Map<String, String> placeholders,
            boolean removeEveryone, long deleteTime, boolean withWebhook,
            boolean withMentions) {
        if (!isValidChannel(channel)) return;

        String rawFormat = format.get();

        // Applies placeholders
        String message = ReplacerUtil.replaceEach(rawFormat, placeholders);
        message = message.replaceAll("&([0-9a-fA-FlLkKrR])", "");
        message = translateEmojis(message, MagiBridge.jda.getTextChannelById(channel).getGuild());

        if (removeEveryone) {
            message = message.replace("@everyone", "");
            message = message.replace("@here", "");
        }

        // Mention discord users if they're mentioned in the message
        List<String> usersMentioned = new ArrayList<>();
        Arrays.stream(message.split(" ")).filter(word ->
                word.startsWith("@")).forEach(usersMentioned::add);
        
        if (!usersMentioned.isEmpty() && withMentions) {
            for (String mention : usersMentioned) {
                List<Member> users = new ArrayList<>();
                MagiBridge.jda.getGuilds().forEach(guild ->
                        guild.getMembers().stream().filter(m ->
                                m.getEffectiveName().equalsIgnoreCase(mention))
                                .forEach(users::add));
                List<Role> roles = MagiBridge.jda.getRolesByName(mention, true);
                if (!users.isEmpty()) {
                    message = message.replace(mention, users.get(0).getAsMention().replace("!", ""));
                }
                if (!roles.isEmpty()) {
                    message = message.replace(mention, roles.get(0).getAsMention());
                }
            }
        }

        if (deleteTime > 0) {
            MagiBridge.jda.getTextChannelById(channel).sendMessage(message)
                    .queue(m -> m.delete().queueAfter(deleteTime, TimeUnit.SECONDS));
        } else if (MagiBridge.getConfig().CHANNELS.USE_WEBHOOKS && withWebhook) {
            message = ReplacerUtil.replaceEach(placeholders.get("%message%"), placeholders);
            message = translateEmojis(message, MagiBridge.jda.getTextChannelById(channel).getGuild());
            if (removeEveryone) {
                message = message.replace("@everyone", "");
                message = message.replace("@here", "");
            }
            Webhooking.sendWebhookMessage(ReplacerUtil.replaceEach(MagiBridge.getConfig().MESSAGES.WEBHOOK_NAME, placeholders),
                    placeholders.get("%player%"),
                    message,
                    channel);
        } else {
            MagiBridge.jda.getTextChannelById(channel).sendMessage(message).queue();
        }
    }

    public static void sendMessageToDiscord(String channel, FormatType format, Map<String, String> placeholders, boolean removeEveryone, long deleteTime, boolean withMentions) {
        sendMessageToDiscord(channel, format, placeholders, removeEveryone, deleteTime, true, withMentions);
    }

    public static void sendMessageToDiscord(String channel, FormatType format, Map<String, String> placeholders, boolean removeEveryone, long deleteTime) {
        sendMessageToDiscord(channel, format, placeholders, removeEveryone, deleteTime, true, true);
    }

    public static void dispatchCommand(MessageReceivedEvent e) {
        String args[] = e.getMessage().getContentDisplay().replace(MagiBridge.getConfig().CHANNELS.CONSOLE_COMMAND + " ", "").split(" ");

        if (!canUseCommand(e.getMember(), args[0])) {
            DiscordHandler.sendMessageToChannel(e.getChannel().getId(), MagiBridge.getConfig().MESSAGES.CONSOLE_NO_PERMISSION);
            return;
        }

        String cmd = e.getMessage().getContentDisplay().replace(MagiBridge.getConfig().CHANNELS.CONSOLE_COMMAND + " ", "");
        Sponge.getCommandManager().process(new BridgeCommandSource(e.getChannel().getId(), Sponge.getServer().getConsole()), cmd);
    }

    public static void dispatchList(Message m, MessageChannel c) {
        StringBuilder players = new StringBuilder();
        boolean shouldDelete = MagiBridge.getConfig().CHANNELS.DELETE_LIST;
        String msg;
        Collection<Player> cplayers = new ArrayList<>();
        Sponge.getServer().getOnlinePlayers().forEach(p -> {
            if (!p.get(Keys.VANISH).orElse(false)) {
                cplayers.add(p);
            }
        });
        if (cplayers.size() == 0) {
            msg = MagiBridge.getConfig().MESSAGES.NO_PLAYERS;
        } else {
            String listformat = MagiBridge.getConfig().MESSAGES.PLAYER_LIST_NAME;
            if (cplayers.size() >= 1) {
                ((ArrayList<Player>) cplayers).sort(Comparator.comparing(Player::getName));
                for (Player player : cplayers) {
                    players.append(listformat
                            .replace("%player%", player.getName())
                            .replace("%topgroup%", GroupUtil.getHighestGroup(player))
                            .replace("%prefix%", player.getOption("prefix")
                                    .orElse(""))).append(", ");
                }
                players = new StringBuilder(players.substring(0, players.length() - 2));
            }
            msg = "**Players online (" + Sponge.getServer().getOnlinePlayers().size() + "/" + Sponge.getServer().getMaxPlayers() + "):** "
                    + "```" + players + "```";
        }
        if (shouldDelete) {
            m.delete().queueAfter(10, TimeUnit.SECONDS);
            sendMessageToChannel(c.getId(), msg, 10);
        } else {
            sendMessageToChannel(c.getId(), msg);
        }
    }

    public static void dispatchWhitelist(Message m, MessageChannel c, String command) {
        String ign = m.getContentDisplay().replace(MagiBridge.getConfig().CHANNELS.WHITELIST_COMMAND, "").trim();

        if (ign == null || ign.isEmpty()) {
            // no name specified
            returnWhitelistMessage("**Correct command usage is **`" + command + " <mc name>`.", m, c);
            return;
        }
        
        Optional<WhitelistService> whitelistClass = Sponge.getServiceManager().provide(WhitelistService.class);
        WhitelistService whitelist;
        
        if (whitelistClass.isPresent()) {
            whitelist = whitelistClass.get();
        } else {
            // whitelist not available
            returnWhitelistMessage("**Error retreiving whitelist!**", m, c);
            return;
        }

        Optional<UserStorageService> userStorageClass;
        UserStorageService userStorage;

        Optional<NucleusNicknameService> nicknameClass;
        NucleusNicknameService nicknameService;

        if (Sponge.getPluginManager().getPlugin("nucleus").isPresent()) {
            // If nucleus is present, fetch nickname service
            userStorageClass = Sponge.getServiceManager().provide(UserStorageService.class);
            
            if (userStorageClass.isPresent()) {
                userStorage = userStorageClass.get();
            } else {
                // whitelist not available
                returnWhitelistMessage("**Error retreiving user database!**", m, c);
                return;
            }

            nicknameClass = Sponge.getServiceManager().provide(NucleusNicknameService.class);

            if (nicknameClass.isPresent()) {
                nicknameService = nicknameClass.get();
            } else {
                // whitelist not available
                returnWhitelistMessage("**Error accessing nickname service!**", m, c);
                return;
            }
        } else {
            returnWhitelistMessage("**Error accessing Nucleus for the nickname service!**", m, c);
            return;
        }

        GameProfileManager profileManager = Sponge.getServer().getGameProfileManager();

        profileManager.get(ign).thenAccept(profile -> {
            if (whitelist.addProfile(profile)) {
                returnWhitelistMessage("**'" + profile.getName().get() + "' was already on the whitelist!**", m, c);
            } else {
                String name = profile.getName().get();
                String nick = m.getAuthor().getName().replace(" ", "_").replaceAll("[^a-zA-Z0-9_]+", "");

                Text.Builder nickTextBuilder = Text.builder(nick);

                org.spongepowered.api.entity.living.player.User user = userStorage.getOrCreate(profile);

                MagiBridge.getLogger().info("user name: " + user.getName());

                Task.Builder taskBuilder = Task.builder();

                taskBuilder.execute(
                    () -> {
                        try {
                            MagiBridge.getLogger().info("attempting to set nick: " + nick);
                            nicknameService.setNickname(user, nickTextBuilder.build(), true);
        
                        } catch (NicknameException e) {
                            MagiBridge.getLogger().info("Adding nick failed with NicknameException");
                            returnWhitelistMessage("**Error when trying to set nickname!** You were added to the whitelist, but please contact someone to get your nick updated.", m, c);
                        } catch (Exception e) {
                            String errorMessage = e.getMessage();
                            MagiBridge.getLogger().info("Adding nick failed with generic Exception: " + errorMessage);
                            returnWhitelistMessage("**Error when trying to set nickname!** You were added to the whitelist, but please contact someone to get your nick updated.", m, c);
                        }

                        MagiBridge.getLogger().info("'" + name + "' added to whitelist");
                        returnWhitelistMessage("**" + name + "** successfully added to whitelist and given nickname '**" + nick + "**'", m, c);
                    }
                ).submit(Sponge.getPluginManager().getPlugin("magibridge").get());

            }
        });
    }

    private static void returnWhitelistMessage(String msg, Message m, MessageChannel c) {
        if (msg != null && msg != "") {
            boolean shouldDelete = MagiBridge.getConfig().CHANNELS.DELETE_WHITELIST;

            if (shouldDelete) {
                m.delete().queueAfter(10, TimeUnit.SECONDS);
                sendMessageToChannel(c.getId(), msg, 10);
            } else {
                sendMessageToChannel(c.getId(), msg);
            }
        }
    }

    private static boolean canUseCommand(Member m, String command) {
        if (MagiBridge.getConfig().CHANNELS.COMMANDS_ROLE_OVERRIDE == null) return false;
        if (MagiBridge.getConfig().CHANNELS.COMMANDS_ROLE_OVERRIDE.get(command) != null) {
            if (MagiBridge.getConfig().CHANNELS.COMMANDS_ROLE_OVERRIDE.get(command).equalsIgnoreCase("everyone")) {
                return true;
            }
        }
        if (m.getRoles().stream().anyMatch(r ->
                r.getName().equalsIgnoreCase(MagiBridge.getConfig().CHANNELS.CONSOLE_REQUIRED_ROLE))) {
            return true;
        }
        return MagiBridge.getConfig().CHANNELS.COMMANDS_ROLE_OVERRIDE.get(command) != null && m.getRoles().stream().anyMatch(role ->
                role.getName().equalsIgnoreCase(MagiBridge.getConfig().CHANNELS.COMMANDS_ROLE_OVERRIDE.get(command)));
    }

    private static String translateEmojis(String message, Guild guild) {
        for (Emote emote : guild.getEmotes()) {
            message = message.replace(":" + emote.getName() + ":", emote.getAsMention());
        }
        return message;
    }

}
