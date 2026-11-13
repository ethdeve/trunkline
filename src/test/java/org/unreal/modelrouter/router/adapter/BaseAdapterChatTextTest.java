package org.unreal.modelrouter.router.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.common.dto.ChatDTO;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O7-c：审核输入文本提取（BaseAdapter.extractChatText）单元测试。
 *
 * <p>审核门闩的完整集成（deny 时上游零调用）需要真实适配器与 mock WebClient，
 * 由企业仓 SOW 集成测试覆盖；此处守住提取逻辑本身的正确性与空安全。</p>
 *
 * @author Trunkline Team
 */
@DisplayName("O7-c：chat 审核文本提取")
class BaseAdapterChatTextTest {

    private static ChatDTO.Message msg(final String role, final String content) {
        // Message = (role, content, name)
        return new ChatDTO.Message(role, content, null);
    }

    /** Request = (model, messages, stream, maxTokens, temperature, topP, topK, frequencyPenalty, presencePenalty, stop, user, options) */
    private static ChatDTO.Request chatOf(final List<ChatDTO.Message> messages) {
        return new ChatDTO.Request("gpt", messages, false,
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("多条消息按顺序换行拼接")
    void joinsMessagesInOrder() {
        final ChatDTO.Request chat = chatOf(List.of(msg("system", "You are helpful."), msg("user", "你好")));
        assertEquals("You are helpful.\n你好", BaseAdapter.extractChatText(chat));
    }

    @Test
    @DisplayName("null / 空消息与空 content 被安全跳过")
    void skipsNullAndEmptyMessages() {
        final List<ChatDTO.Message> messages = new ArrayList<>();
        messages.add(null);
        messages.add(msg("user", ""));
        messages.add(msg("user", null));
        messages.add(msg("user", "real"));

        assertEquals("real", BaseAdapter.extractChatText(chatOf(messages)));
    }

    @Test
    @DisplayName("null 请求 / null messages 返回空串（不抛异常）")
    void nullSafe() {
        assertEquals("", BaseAdapter.extractChatText(null));
        assertEquals("", BaseAdapter.extractChatText(chatOf(null)));
    }

    @Test
    @DisplayName("全部为空时返回空串（审核方可按空内容自行裁决）")
    void allEmptyYieldsEmptyString() {
        final String text = BaseAdapter.extractChatText(chatOf(List.of(msg("user", ""))));
        assertNotNull(text);
        assertTrue(text.isEmpty());
    }
}
