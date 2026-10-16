package dev.saseq.services;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MessageHistoryTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "@everyone"})
    void bothHistoryToolsIncludeAlertDetailsWhenTextHasNoDetails(String content) {
        Message message = message(content);
        when(message.getEmbeds()).thenReturn(List.of(new EmbedBuilder()
                .setTitle("Service down", "https://example.com/status")
                .setDescription("Connection timed out\nPlease investigate")
                .addField("Monitor", "Demo service", true)
                .addField("Status", "Down", true)
                .setAuthor("Demo monitor")
                .setFooter("Synthetic alert")
                .setTimestamp(OffsetDateTime.parse("2026-10-16T04:43:00Z"))
                .setColor(0xff0000)
                .setImage("https://example.com/chart.png")
                .setThumbnail("https://example.com/icon.png")
                .build()));

        JDA jda = mock(JDA.class);
        TextChannel channel = mock(TextChannel.class, RETURNS_DEEP_STUBS);
        when(jda.getTextChannelById("123")).thenReturn(channel);
        when(channel.getHistory().retrievePast(1).complete()).thenReturn(List.of(message));

        Guild guild = mock(Guild.class, RETURNS_DEEP_STUBS);
        Member member = mock(Member.class);
        User user = mock(User.class, RETURNS_DEEP_STUBS);
        when(jda.getGuilds()).thenReturn(List.of(guild));
        when(guild.retrieveMemberById("456").complete()).thenReturn(member);
        when(member.getUser()).thenReturn(user);
        PrivateChannel privateChannel = mock(PrivateChannel.class, RETURNS_DEEP_STUBS);
        when(user.openPrivateChannel().complete()).thenReturn(privateChannel);
        when(privateChannel.getHistory().retrievePast(1).complete())
                .thenReturn(List.of(message));

        String channelResult = new MessageService(jda).readMessages("123", "1", null, null, null);
        String privateResult = new UserService(jda).readPrivateMessages("456", "1", null, null, null);
        assertThat(channelResult).isEqualTo(privateResult)
                .contains("Retrieved 1 messages", "```" + content + "```", "Embeds (JSON):",
                        "Service down", "https://example.com/status", "Connection timed out",
                        "Please investigate", "Monitor", "Demo service", "Status", "Down",
                        "Demo monitor", "Synthetic alert", "2026-10-16T04:43",
                        "16711680", "https://example.com/chart.png", "https://example.com/icon.png");
    }

    @Test
    void preservesPlainTextAndAttachmentOutput() {
        Message message = message("Normal message");
        Message.Attachment attachment = mock(Message.Attachment.class);
        when(attachment.getId()).thenReturn("789");
        when(attachment.getFileName()).thenReturn("report.txt");
        when(attachment.getSize()).thenReturn(100);
        when(attachment.getContentType()).thenReturn("text/plain");
        when(attachment.getUrl()).thenReturn("https://example.com/report.txt");
        when(message.getAttachments()).thenReturn(List.of(attachment));

        assertThat(MessageFormatter.formatMessages(List.of(message))).containsExactly(
                "- (ID: 123456) **[Demo bot]** `2026-10-16T04:43Z`: ```Normal message```"
                        + "\n  Attachments:\n    - (Attachment ID: 789) `report.txt` (100 B, text/plain)"
                        + " URL: https://example.com/report.txt");
    }

    @Test
    void preservesMultipleEmbedsIncludingImageOnlyEmbedsAndAttachments() {
        Message message = message("");
        when(message.getEmbeds()).thenReturn(List.of(
                new EmbedBuilder().setImage("https://example.com/chart.png").build(),
                new EmbedBuilder().addField("Recovery", "Service restored", false).build()));
        Message.Attachment attachment = mock(Message.Attachment.class);
        when(attachment.getFileName()).thenReturn("report.txt");
        when(message.getAttachments()).thenReturn(List.of(attachment));

        assertThat(MessageFormatter.formatMessages(List.of(message)).get(0))
                .contains("report.txt", "https://example.com/chart.png", "Recovery", "Service restored")
                .doesNotContain("\"title\":null");
        assertThat(MessageFormatter.formatMessages(List.of())).isEmpty();
    }

    private Message message(String content) {
        Message message = mock(Message.class, RETURNS_DEEP_STUBS);
        when(message.getId()).thenReturn("123456");
        when(message.getAuthor().getName()).thenReturn("Demo bot");
        when(message.getTimeCreated()).thenReturn(OffsetDateTime.parse("2026-10-16T04:43:00Z"));
        when(message.getContentDisplay()).thenReturn(content);
        when(message.getEmbeds()).thenReturn(List.of());
        when(message.getAttachments()).thenReturn(List.of());
        return message;
    }
}
