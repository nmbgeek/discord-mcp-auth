package dev.saseq.services;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpstreamToolsTest {
    private final JDA jda = mock(JDA.class, RETURNS_DEEP_STUBS);
    private final TextChannel channel = mock(TextChannel.class, RETURNS_DEEP_STUBS);
    private final MessageService messages = new MessageService(jda);

    @Test
    void searchWalksPagesAndPreservesEmbedsAndReportsExactLimitCoverage() {
        when(jda.getTextChannelById("123")).thenReturn(channel);
        Message match = message(200, "ALERT recovered");
        MessageHistory history = mock(MessageHistory.class, RETURNS_DEEP_STUBS);
        when(channel.getHistory()).thenReturn(history);
        Message ordinary = message(300, "ordinary message");
        when(history.retrievePast(100).complete()).thenReturn(
                java.util.Collections.nCopies(100, ordinary));
        when(history.retrievePast(1).complete()).thenReturn(List.of(match), List.of());
        clearInvocations(history);

        assertThat(messages.searchMessages("123", "alert", "101"))
                .contains("Found 1 messages", "scanned 101 messages", "whole history was scanned",
                        "ALERT recovered", "Embeds (JSON):", "Monitor details", "Author ID: 456");
        verify(history, times(2)).retrievePast(1);
    }

    @Test
    void searchReportsWhenOlderMessagesRemain() {
        when(jda.getTextChannelById("123")).thenReturn(channel);
        Message recent = message(200, "recent");
        Message older = message(100, "older");
        when(channel.getHistory().retrievePast(1).complete())
                .thenReturn(List.of(recent), List.of(older));
        assertThat(messages.searchMessages("123", "missing", "1"))
                .contains("No messages found", "scanned 1 messages", "older messages were not scanned");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1001", "invalid"})
    void scanToolsRejectInvalidLimitsBeforeMakingRequests(String limit) {
        assertThatIllegalArgumentException().isThrownBy(() -> messages.searchMessages("123", "text", limit));
        assertThatIllegalArgumentException().isThrownBy(() -> messages.getMessageReplies("123", "100", limit));
        verifyNoInteractions(jda);
    }

    @Test
    void getsMessageWithItsReferencedMessageAndRetainsEmbeds() {
        when(jda.getTextChannelById("123")).thenReturn(channel);
        Message reply = reply(200, MessageType.INLINE_REPLY, "100");
        Message original = message(100, "original");
        when(reply.getReferencedMessage()).thenReturn(original);
        when(channel.retrieveMessageById("200").complete()).thenReturn(reply);
        assertThat(messages.getMessage("123", "200"))
                .contains("[Reply to: 100]", "Replying to:", "original", "Embeds (JSON):", "Jump URL:");

        when(reply.getReferencedMessage()).thenReturn(null);
        assertThat(messages.getMessage("123", "200"))
                .contains("Replying to message 100 (not available");
    }

    @Test
    void repliesWalkForwardSortChronologicallyAndExcludeNonReplies() {
        when(jda.getTextChannelById("123")).thenReturn(channel);
        MessageHistory history = mock(MessageHistory.class, RETURNS_DEEP_STUBS);
        when(channel.getHistoryAfter("100", 4).complete()).thenReturn(history);
        List<Message> firstPage = List.of(
                reply(300, MessageType.INLINE_REPLY, "100"),
                reply(200, MessageType.INLINE_REPLY, "100"));
        List<Message> secondPage = List.of(
                reply(400, MessageType.DEFAULT, "100"),
                reply(500, MessageType.INLINE_REPLY, "999"));
        when(history.getRetrievedHistory()).thenReturn(firstPage);
        when(history.retrieveFuture(2).complete()).thenReturn(secondPage);
        when(history.retrieveFuture(1).complete()).thenReturn(List.of());

        String result = messages.getMessageReplies("123", "100", "4");
        assertThat(result).contains("Found 2 replies", "scanned 4 messages", "up to the latest message",
                        "Embeds (JSON):", "[Reply to: 100]")
                .doesNotContain("ID: 400", "ID: 500");
        assertThat(result.indexOf("ID: 200")).isLessThan(result.indexOf("ID: 300"));
    }

    @Test
    void repliesReportAnIncompleteWindow() {
        when(jda.getTextChannelById("123")).thenReturn(channel);
        MessageHistory history = mock(MessageHistory.class, RETURNS_DEEP_STUBS);
        when(channel.getHistoryAfter("100", 1).complete()).thenReturn(history);
        Message unrelated = message(200, "unrelated");
        Message laterReply = reply(300, MessageType.INLINE_REPLY, "100");
        when(history.getRetrievedHistory()).thenReturn(List.of(unrelated));
        when(history.retrieveFuture(1).complete()).thenReturn(List.of(laterReply));
        assertThat(messages.getMessageReplies("123", "100", "1"))
                .contains("No replies", "scanned 1 messages", "newer messages were not scanned");
    }

    @ParameterizedTest
    @EnumSource(value = MessageType.class, names = {"INLINE_REPLY", "DEFAULT", "THREAD_STARTER_MESSAGE"})
    void sharedFormatterOnlyMarksActualReplies(MessageType type) {
        String result = MessageFormatter.formatMessages(List.of(reply(200, type, "100"))).get(0);
        assertThat(result.contains("[Reply to: 100]")).isEqualTo(type == MessageType.INLINE_REPLY);
    }

    @Test
    void mutationFailuresPropagateInsteadOfReportingSuccess() {
        when(jda.getTextChannelById("123")).thenReturn(channel);
        Message message = message(200, "test");
        when(channel.retrieveMessageById("200").complete()).thenReturn(message);
        RuntimeException denied = new IllegalStateException("Discord rejected mutation");
        when(message.delete().complete()).thenThrow(denied);
        when(message.addReaction(any(Emoji.class)).complete()).thenThrow(denied);
        when(message.removeReaction(any(Emoji.class)).complete()).thenThrow(denied);
        assertThatThrownBy(() -> messages.deleteMessage("123", "200")).isSameAs(denied);
        assertThatThrownBy(() -> messages.addReaction("123", "200", "👍")).isSameAs(denied);
        assertThatThrownBy(() -> messages.removeReaction("123", "200", "👍")).isSameAs(denied);
    }

    @Test
    void forumThreadListingCombinesActiveAndArchivedWithoutDuplicates() {
        ForumChannel forum = mock(ForumChannel.class, RETURNS_DEEP_STUBS);
        when(jda.getGuildChannelById("123")).thenReturn(forum);
        ThreadChannel active = thread("200", false);
        ThreadChannel archived = thread("300", true);
        when(forum.getThreadChannels()).thenReturn(List.of(active));
        when(forum.retrieveArchivedPublicThreadChannels().limit(100).complete())
                .thenReturn(List.of(active, archived));
        assertThat(new ThreadService(jda).listThreads("123"))
                .contains("Retrieved 2 threads", "ID: 200", "ID: 300", "Archived: true");
    }

    @Test
    void threadListingReturnsActiveThreadsWhenArchivedHistoryIsForbidden() {
        when(jda.getGuildChannelById("123")).thenReturn(channel);
        ThreadChannel active = thread("200", false);
        when(channel.getThreadChannels()).thenReturn(List.of(active));
        var denied = new InsufficientPermissionException(channel, Permission.MESSAGE_HISTORY);
        when(channel.retrieveArchivedPublicThreadChannels()).thenThrow(denied);
        assertThat(new ThreadService(jda).listThreads("123"))
                .contains("Retrieved 1 threads", "Archived threads were not listed");
    }

    @Test
    void threadInfoHandlesUncachedOwnerAndMissingThread() {
        ThreadChannel thread = thread("200", true);
        when(thread.getOwner()).thenReturn(null);
        when(thread.getOwnerId()).thenReturn("456");
        when(jda.getThreadChannelById("200")).thenReturn(thread);
        ThreadService service = new ThreadService(jda);
        assertThat(service.getThreadInfo("200")).contains("Archived: true", "unknown (ID: 456)");
        when(jda.getThreadChannelById("300")).thenReturn(null);
        assertThatIllegalArgumentException().isThrownBy(() -> service.getThreadInfo("300"))
                .withMessageContaining("call list_threads");
    }

    @Test
    void memberLookupRefreshesIdentityAndIncludesRoles() {
        Guild guild = mock(Guild.class, RETURNS_DEEP_STUBS);
        Member member = mock(Member.class, RETURNS_DEEP_STUBS);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.retrieveMemberById("456").useCache(false).complete()).thenReturn(member);
        when(member.getUser().getId()).thenReturn("456");
        when(member.getUser().getName()).thenReturn("demo");
        when(member.getNickname()).thenReturn("Demo nickname");
        when(member.getRoles()).thenReturn(List.of());
        assertThat(new UserService(jda).getMemberById("456", "123"))
                .contains("User ID: 456", "Username: demo", "Demo nickname", "Roles: none");
    }

    @Test
    void memberSearchUsesNamesRatherThanPartialIdsAndSurvivesPrefixFailure() {
        Guild guild = mock(Guild.class, RETURNS_DEEP_STUBS);
        Member match = mock(Member.class, RETURNS_DEEP_STUBS);
        Member wrong = mock(Member.class, RETURNS_DEEP_STUBS);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(match.getId()).thenReturn("456");
        when(match.getUser().getId()).thenReturn("456");
        when(match.getNickname()).thenReturn("Demo nickname");
        when(wrong.getId()).thenReturn("demo");
        when(guild.getMemberCache().iterator()).thenAnswer(invocation -> List.of(wrong, match).iterator());
        when(guild.retrieveMembersByPrefix("dEmO", 25).get()).thenThrow(new IllegalStateException("unavailable"));
        assertThat(new UserService(jda).searchMembers("dEmO", null, "123"))
                .contains("Found 1 member candidate", "User ID: 456");
    }

    private Message message(long id, String content) {
        Message message = mock(Message.class, RETURNS_DEEP_STUBS);
        when(message.getId()).thenReturn(Long.toString(id));
        when(message.getIdLong()).thenReturn(id);
        when(message.getAuthor().getName()).thenReturn("Demo");
        when(message.getAuthor().getId()).thenReturn("456");
        when(message.getContentDisplay()).thenReturn(content);
        when(message.getTimeCreated()).thenReturn(OffsetDateTime.parse("2026-10-16T04:43:00Z"));
        when(message.getEmbeds()).thenReturn(List.of(new EmbedBuilder().setDescription("Monitor details").build()));
        when(message.getAttachments()).thenReturn(List.of());
        return message;
    }

    private Message reply(long id, MessageType type, String target) {
        Message message = message(id, "reply");
        when(message.getType()).thenReturn(type);
        when(message.getMessageReference().getMessageId()).thenReturn(target);
        return message;
    }

    private ThreadChannel thread(String id, boolean archived) {
        ThreadChannel thread = mock(ThreadChannel.class, RETURNS_DEEP_STUBS);
        when(thread.getId()).thenReturn(id);
        when(thread.getName()).thenReturn("thread-" + id);
        when(thread.isArchived()).thenReturn(archived);
        when(thread.getType()).thenReturn(ChannelType.GUILD_PUBLIC_THREAD);
        return thread;
    }
}
