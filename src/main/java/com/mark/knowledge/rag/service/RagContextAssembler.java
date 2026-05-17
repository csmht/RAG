package com.mark.knowledge.rag.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 上下文装配服务。
 */
@Service
public class RagContextAssembler {

    private static final Pattern CONTEXT_REFERENCE_PATTERN = Pattern.compile("(这个|这个问题|这个方案|这个接口|这个方法|这个类|这个配置|这里|这一步|这块|该|它|其|他们|它们|那|那个|那段|那种|上述|上面|前者|后者)");
    private static final Pattern FOLLOW_UP_ACTION_PATTERN = Pattern.compile("^(继续|再说|展开|补充|对比|比较|细说|详细说说|具体说说|接着说|然后呢|还有呢)");
    private static final Pattern QUESTION_WORD_PATTERN = Pattern.compile("(什么|为何|为什么|怎么|如何|多少|哪些|哪种|哪个|是否|能否|可否|有无|多久)");

    /**
     * 按需补全上下文依赖问题，避免默认使用模型重写检索词。
     */
    public String rewriteQuestion(String question, ConversationMemoryService.ConversationMemorySnapshot memory, int memoryTopMatchMaxLength, int memoryIntentMaxSourceLength) {
        if (!StringUtils.hasText(question)) {
            return question;
        }
        String trimmedQuestion = question.trim();
        if (!shouldRewriteQuestion(trimmedQuestion, memory)) {
            return trimmedQuestion;
        }

        String anchor = resolveRewriteAnchor(memory, memoryTopMatchMaxLength, memoryIntentMaxSourceLength);
        if (!StringUtils.hasText(anchor)) {
            return trimmedQuestion;
        }

        String normalizedAnchor = anchor.trim();
        if (trimmedQuestion.contains(normalizedAnchor)) {
            return trimmedQuestion;
        }
        return normalizedAnchor + "：" + trimmedQuestion;
    }

    /**
     * 构建最终回答阶段使用的 Prompt。
     */
    public String buildPrompt(ConversationMemoryService.ConversationMemorySnapshot memory, String context, String question) {
        String memoryContext = buildMemoryContext(memory);
        String safeMemoryContext = StringUtils.hasText(memoryContext) ? memoryContext : "无";

        return String.format("""
            你是一个基于文档内容回答问题的助手。
            你必须严格依据下面提供的会话记忆和文档上下文回答。
            如果上下文中没有答案，请明确说明“根据已上传文档无法回答该问题”。
            不要编造，不要补充上下文之外的事实。

            会话记忆：
            %s

            文档上下文：
            %s

            用户当前问题：%s

            请直接给出中文答案：""", safeMemoryContext, context, question);
    }

    /**
     * 将结构化记忆整理为可注入 Prompt 的标准文本块。
     */
    public String buildMemoryContext(ConversationMemoryService.ConversationMemorySnapshot memory) {
        if (memory == null) {
            return "";
        }

        List<String> sections = new ArrayList<>();
        if (StringUtils.hasText(memory.intent())) {
            sections.add("当前意图：\n" + memory.intent());
        }
        if (memory.facts() != null && !memory.facts().isEmpty()) {
            sections.add("已确认事实：\n" + memory.facts().stream()
                .map(fact -> "- " + fact)
                .collect(Collectors.joining("\n")));
        }
        if (StringUtils.hasText(memory.summary())) {
            sections.add("历史摘要：\n" + memory.summary());
        }
        if (memory.recentMessages() != null && !memory.recentMessages().isEmpty()) {
            sections.add("最近对话：\n" + formatHistory(memory.recentMessages()));
        }
        return String.join("\n\n", sections);
    }

    /**
     * 按最大长度截断文本，优先保留尾部最新内容。
     */
    public String trimToMaxLength(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        int safeMaxLength = Math.max(maxLength, 1);
        if (value.length() <= safeMaxLength) {
            return value;
        }
        return value.substring(value.length() - safeMaxLength);
    }

    /**
     * 判断当前问题是否需要结合细化规则做增强。
     */
    private boolean shouldRewriteQuestion(String question, ConversationMemoryService.ConversationMemorySnapshot memory) {
        if (memory == null || !hasAnyMemoryAnchor(memory)) {
            return false;
        }
        if (!StringUtils.hasText(question)) {
            return false;
        }

        String trimmedQuestion = question.trim();
        if (isFollowUpQuestion(trimmedQuestion)) {
            return true;
        }
        if (isContextReferenceQuestion(trimmedQuestion)) {
            return true;
        }
        return shouldEnhanceGeneralQuestion(trimmedQuestion, memory);
    }

    /**
     * 判断问题是否属于延续性追问。
     */
    private boolean isFollowUpQuestion(String question) {
        if (FOLLOW_UP_ACTION_PATTERN.matcher(question).find()) {
            return true;
        }
        return question.length() <= 10 && QUESTION_WORD_PATTERN.matcher(question).find();
    }

    /**
     * 判断问题是否包含明显的上下文指代。
     */
    private boolean isContextReferenceQuestion(String question) {
        if (CONTEXT_REFERENCE_PATTERN.matcher(question).find()) {
            return true;
        }
        return question.length() <= 6;
    }

    /**
     * 判断普通问题是否需要结合记忆做增强。
     */
    private boolean shouldEnhanceGeneralQuestion(String question, ConversationMemoryService.ConversationMemorySnapshot memory) {
        if (!QUESTION_WORD_PATTERN.matcher(question).find()) {
            return false;
        }
        if (StringUtils.hasText(memory.intent()) && !question.contains(memory.intent().trim())) {
            return true;
        }
        if (memory.facts() != null) {
            for (String fact : memory.facts()) {
                if (StringUtils.hasText(fact) && containsOverlapKeyword(question, fact)) {
                    return true;
                }
            }
        }
        return question.length() <= 14;
    }

    /**
     * 判断问题与事实锚点是否存在关键词重叠。
     */
    private boolean containsOverlapKeyword(String question, String anchor) {
        String normalizedQuestion = question.replace("：", " ").replace("，", " ").replace("。", " ").trim();
        String normalizedAnchor = anchor.replace("：", " ").replace("，", " ").replace("。", " ").trim();
        if (!StringUtils.hasText(normalizedQuestion) || !StringUtils.hasText(normalizedAnchor)) {
            return false;
        }
        for (String token : normalizedAnchor.split("\\s+")) {
            if (token.length() >= 2 && normalizedQuestion.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断会话记忆中是否存在可用于补全的锚点信息。
     */
    private boolean hasAnyMemoryAnchor(ConversationMemoryService.ConversationMemorySnapshot memory) {
        return StringUtils.hasText(memory.intent())
            || (memory.facts() != null && !memory.facts().isEmpty())
            || (memory.recentMessages() != null && !memory.recentMessages().isEmpty())
            || StringUtils.hasText(memory.summary());
    }

    /**
     * 选择最适合补全问题的上下文锚点。
     */
    private String resolveRewriteAnchor(ConversationMemoryService.ConversationMemorySnapshot memory, int memoryTopMatchMaxLength, int memoryIntentMaxSourceLength) {
        if (memory == null) {
            return null;
        }
        if (StringUtils.hasText(memory.intent())) {
            return memory.intent().trim();
        }
        if (memory.facts() != null) {
            for (String fact : memory.facts()) {
                if (StringUtils.hasText(fact)) {
                    return trimToMaxLength(fact.trim(), memoryTopMatchMaxLength);
                }
            }
        }
        if (memory.recentMessages() != null) {
            for (int i = memory.recentMessages().size() - 1; i >= 0; i--) {
                ConversationMemoryService.ConversationMessage message = memory.recentMessages().get(i);
                if (message.role() == ConversationMemoryService.ConversationRole.USER && StringUtils.hasText(message.content())) {
                    return trimToMaxLength(message.content().trim(), memoryIntentMaxSourceLength);
                }
            }
        }
        if (StringUtils.hasText(memory.summary())) {
            return trimToMaxLength(memory.summary().trim(), memoryIntentMaxSourceLength);
        }
        return null;
    }

    /**
     * 将最近对话格式化为可读的角色文本。
     */
    private String formatHistory(List<ConversationMemoryService.ConversationMessage> history) {
        return history.stream()
            .map(message -> (message.role() == ConversationMemoryService.ConversationRole.USER ? "用户：" : "助手：")
                + message.content())
            .collect(Collectors.joining("\n"));
    }
}
