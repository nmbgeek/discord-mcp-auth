package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class EmojiService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public EmojiService(JDA jda) {
        this.jda = jda;
    }

    private String resolveGuildId(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            return defaultGuildId;
        }
        return guildId;
    }

    private Guild getGuild(String guildId) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        return guild;
    }

    @Tool(name = "list_emojis", description = "List all custom emojis on the server")
    public String listEmojis(
            @ToolParam(description = "Discord server ID", required = false) String guildId) {

        Guild guild = getGuild(guildId);
        List<RichCustomEmoji> emojis = guild.retrieveEmojis().complete();

        if (emojis.isEmpty()) {
            return "No custom emojis found on this server.";
        }

        return "Retrieved " + emojis.size() + " custom emojis:\n" +
                emojis.stream()
                        .map(e -> "- **" + e.getName() + "** (ID: " + e.getId() + ")" +
                                " | Animated: " + e.isAnimated() +
                                (e.getRoles().isEmpty() ? "" : " | Roles: " +
                                        e.getRoles().stream().map(Role::getName).collect(Collectors.joining(", "))))
                        .collect(Collectors.joining("\n"));
    }

    @Tool(name = "get_emoji_details", description = "Get detailed information about a specific custom emoji")
    public String getEmojiDetails(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the emoji") String emojiId) {

        Guild guild = getGuild(guildId);
        if (emojiId == null || emojiId.isEmpty()) {
            throw new IllegalArgumentException("emojiId cannot be null");
        }

        RichCustomEmoji emoji = guild.retrieveEmojiById(emojiId).complete();

        StringBuilder sb = new StringBuilder();
        sb.append("Emoji: **").append(emoji.getName()).append("** (ID: ").append(emoji.getId()).append(")\n");
        sb.append("  • Animated: ").append(emoji.isAnimated()).append("\n");
        sb.append("  • Managed: ").append(emoji.isManaged()).append("\n");
        sb.append("  • Available: ").append(emoji.isAvailable()).append("\n");
        sb.append("  • Image URL: ").append(emoji.getImageUrl());
        if (!emoji.getRoles().isEmpty()) {
            sb.append("\n  • Restricted to roles: ").append(
                    emoji.getRoles().stream()
                            .map(r -> r.getName() + " (ID: " + r.getId() + ")")
                            .collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    @Tool(name = "create_emoji", description = "Upload a base64 PNG, JPEG, or GIF emoji (data URI or raw). Max 256 KiB and 1024x1024 pixels. URL downloads are disabled.")
    public String createEmoji(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Emoji name (2-32 chars, alphanumeric and underscores only)") String name,
            @ToolParam(description = "Image as base64 Data URI (e.g. data:image/png;base64,...) or raw base64 string", required = false) String image,
            @ToolParam(description = "Unsupported legacy parameter. URL downloads are disabled; supply base64 in image instead.", required = false) String imageUrl,
            @ToolParam(description = "Comma-separated role IDs to restrict emoji usage (empty = everyone)", required = false) String roles) {

        name = validateAndNormalizeName(name);
        var icon = EmojiImage.from(image, imageUrl);
        Guild guild = getGuild(guildId);

        Role[] roleArray = parseRoles(guild, roles);
        RichCustomEmoji emoji = guild.createEmoji(name, icon, roleArray).complete();

        return "Created emoji **" + emoji.getName() + "** (ID: " + emoji.getId() + ")" +
                "\n  • Animated: " + emoji.isAnimated() +
                "\n  • Image URL: " + emoji.getImageUrl();
    }

    @Tool(name = "edit_emoji", description = "Edit an existing emoji's name or role restrictions")
    public String editEmoji(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the emoji to edit") String emojiId,
            @ToolParam(description = "New name for the emoji", required = false) String name,
            @ToolParam(description = "Comma-separated role IDs to restrict usage (empty string = unrestrict for everyone)", required = false) String roles) {

        Guild guild = getGuild(guildId);
        if (emojiId == null || emojiId.isEmpty()) {
            throw new IllegalArgumentException("emojiId cannot be null");
        }

        RichCustomEmoji emoji = guild.retrieveEmojiById(emojiId).complete();
        var manager = emoji.getManager();

        if (name != null && !name.isEmpty()) {
            manager.setName(validateAndNormalizeName(name));
        }
        if (roles != null) {
            if (roles.isEmpty()) {
                manager.setRoles(null);
            } else {
                manager.setRoles(Arrays.stream(roles.split(","))
                        .map(String::trim)
                        .map(id -> {
                            Role role = guild.getRoleById(id);
                            if (role == null) throw new IllegalArgumentException("Role not found: " + id);
                            return role;
                        })
                        .collect(Collectors.toSet()));
            }
        }

        manager.complete();
        RichCustomEmoji updated = guild.retrieveEmojiById(emojiId).complete();
        return "Updated emoji **" + updated.getName() + "** (ID: " + updated.getId() + ")";
    }

    @Tool(name = "delete_emoji", description = "Permanently delete a custom emoji from the server")
    public String deleteEmoji(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the emoji to delete") String emojiId) {

        Guild guild = getGuild(guildId);
        if (emojiId == null || emojiId.isEmpty()) {
            throw new IllegalArgumentException("emojiId cannot be null");
        }

        RichCustomEmoji emoji = guild.retrieveEmojiById(emojiId).complete();
        String name = emoji.getName();
        emoji.delete().complete();
        return "Deleted emoji **" + name + "** (ID: " + emojiId + ")";
    }

    private String validateAndNormalizeName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Emoji name cannot be null or empty");
        }
        name = name.replaceAll("\\s+", "_");
        if (name.length() < 2 || name.length() > 32) {
            throw new IllegalArgumentException("Emoji name must be between 2 and 32 characters");
        }
        if (!name.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Emoji name can only contain letters, numbers, and underscores");
        }
        return name;
    }

    private Role[] parseRoles(Guild guild, String roles) {
        if (roles == null || roles.isEmpty()) {
            return new Role[0];
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(id -> {
                    Role role = guild.getRoleById(id);
                    if (role == null) throw new IllegalArgumentException("Role not found: " + id);
                    return role;
                })
                .toArray(Role[]::new);
    }
}
