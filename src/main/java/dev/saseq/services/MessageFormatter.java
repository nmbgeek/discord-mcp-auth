package dev.saseq.services;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.util.List;

/** Shared output for channel and private-message history. */
final class MessageFormatter {
    private MessageFormatter() {
    }

    static List<String> formatMessages(List<Message> messages) {
        return messages.stream().map(message -> {
            StringBuilder output = new StringBuilder(String.format(
                    "- (ID: %s) **[%s]** `%s`: ```%s```",
                    message.getId(), message.getAuthor().getName(),
                    message.getTimeCreated(), message.getContentDisplay()));

            if (!message.getAttachments().isEmpty()) {
                output.append("\n  Attachments:");
                for (Message.Attachment attachment : message.getAttachments()) {
                    output.append("\n    - ").append(formatAttachmentSummary(attachment));
                }
            }

            if (!message.getEmbeds().isEmpty()) {
                output.append("\n  Embeds (JSON):");
                for (MessageEmbed embed : message.getEmbeds()) {
                    // Preserve named fields and metadata, including embed-only webhook alerts.
                    output.append("\n    - ").append(embed.toData().toString());
                }
            }
            return output.toString();
        }).toList();
    }

    static String formatAttachmentSummary(Message.Attachment attachment) {
        return String.format(
                "(Attachment ID: %s) `%s` (%s, %s) URL: %s",
                attachment.getId(), attachment.getFileName(), formatFileSize(attachment.getSize()),
                attachment.getContentType() != null ? attachment.getContentType() : "unknown",
                attachment.getUrl());
    }

    private static String formatFileSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
