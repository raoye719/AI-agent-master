package com.yupi.yuaiagent.agent;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yupi.yuaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


/**
 * 處理工具調用的基礎代理類，具體實現了think和act方法，可以創建實例的父類
 */
@EqualsAndHashCode(callSuper=true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {
    // TODO 定義的這些全局變量分別有什麽用
    // 可用的工具
    private final ToolCallback[] availableTools;

    // 保存了工具調用信息的相應
    private ChatResponse toolCallChatResponse;

    // 工具調用管理者
    private final ToolCallingManager toolCallingManager;

    // 禁用内置的工具調用機制 自己維護上下文
    private final ChatOptions chatOptions;

    // 工具調用歷史記錄（用於循環檢測）
    private List<String> toolCallHistory = new ArrayList<>();

    public ToolCallAgent(ToolCallback[] availableTools) {
        // TODO 爲什麽要用super
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        // 禁用内置的工具調用機制 自己維護上下文
        this.chatOptions = DashScopeChatOptions.builder()
                .withInternalToolExecutionEnabled(false)
                .build();
    }





    @Override
    public boolean think() {
        if(getNextStepPrompt() != null && !getNextStepPrompt().isEmpty()){
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            getMessageList().add(userMessage);
        }
        List<Message> messageList = getMessageList();
        Prompt prompt = new Prompt(messageList, chatOptions);

        // 獲取帶工具選項的相應
        try {
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(getSystemPrompt())
                    .toolCallbacks(availableTools)
                    .call()
                    .chatResponse();

            // 記錄相應 用於Act
            this.toolCallChatResponse = chatResponse;
            // TODO 啥是助手信息
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            // 輸出提示信息
            String result = assistantMessage.getText();
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            log.info(getName() + "的思考：" + result);
            // TODO 一次think不是只能選擇一個工具？
            log.info(getName() + "選擇了" + toolCallList.size() + "個工具使用");
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名稱: %s, 參數: %s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);
            
            // 記錄工具調用信息用於循環檢測
            if (!toolCallList.isEmpty()) {
                String toolCallSignature = toolCallList.stream()
                        .map(toolCall -> toolCall.name() + ":" + toolCall.arguments())
                        .collect(Collectors.joining("|"));
                toolCallHistory.add(toolCallSignature);
            }
            
            if(toolCallList.isEmpty()){
                // 只有不調用工具時 才記錄助手信息
                getMessageList().add(assistantMessage);
                return false;
            }else {
                return true;
            }
        } catch (Exception e) {
            log.error(getName() + "的思考過程遇到了問題" + e.getMessage());
            getMessageList().add(new AssistantMessage("處理時遇到錯誤" + e.getMessage()));
            return false;
        }
    }

    /**
     * 執行工具調用結果
     * @return 執行結果
     */
    @Override
    public String act() {
        if(!toolCallChatResponse.hasToolCalls()){
            return "沒有工具調用";
        }
        // 調用工具
        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        // 記錄上下文。conversationHistory已經包含了助手消息和工具調用返回的結果
        // 以前的歷史上下文不斷叠加
        setMessageList(toolExecutionResult.conversationHistory());
        log.info("現在的歷史上下文信息：{}", getMessageList());
        // 當前工具調用的結果
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        // 判断是否调用了终止工具
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate"));
        if (terminateToolCalled) {
            // 任务结束，更改状态
            setState(AgentState.FINISHED);
        }
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 返回的结果：" + response.responseData())
                .collect(Collectors.joining("\n"));
        log.info(results);
        return results;
    }

    /**
     * 重寫循環檢測方法，專門檢測工具調用的重複模式
     *
     * @return 是否陷入循環
     */
    @Override
    protected boolean isStuck() {
        // 首先檢查基礎的步驟結果重複
        if (super.isStuck()) {
            return true;
        }

        // 檢查工具調用的重複模式
        return isToolCallStuck();
    }

    /**
     * 檢查工具調用是否陷入循環
     *
     * @return 是否陷入工具調用循環
     */
    private boolean isToolCallStuck() {
        if (toolCallHistory.size() < getDuplicateThreshold() + 1) {
            return false;
        }

        String lastToolCall = toolCallHistory.get(toolCallHistory.size() - 1);
        if (lastToolCall == null || lastToolCall.trim().isEmpty()) {
            return false;
        }

        // 計算相同工具調用的重複次數
        int duplicateCount = 0;
        for (int i = toolCallHistory.size() - 2; i >= 0; i--) {
            String toolCall = toolCallHistory.get(i);
            if (toolCall != null && isSimilarToolCall(lastToolCall, toolCall)) {
                duplicateCount++;
            }
        }

        return duplicateCount >= getDuplicateThreshold();
    }

    /**
     * 檢查兩個工具調用是否相似
     *
     * @param toolCall1 工具調用1
     * @param toolCall2 工具調用2
     * @return 是否相似
     */
    private boolean isSimilarToolCall(String toolCall1, String toolCall2) {
        if (toolCall1 == null || toolCall2 == null) {
            return false;
        }

        // 提取工具名稱
        String toolName1 = extractToolName(toolCall1);
        String toolName2 = extractToolName(toolCall2);

        // 如果工具名稱不同，則不是重複
        if (!toolName1.equals(toolName2)) {
            return false;
        }

        // 如果工具名稱相同，檢查參數相似度
        return isSimilarResult(toolCall1, toolCall2);
    }

    /**
     * 從工具調用簽名中提取工具名稱
     *
     * @param toolCallSignature 工具調用簽名
     * @return 工具名稱
     */
    private String extractToolName(String toolCallSignature) {
        if (toolCallSignature == null || !toolCallSignature.contains(":")) {
            return "";
        }
        return toolCallSignature.split(":")[0];
    }

    /**
     * 重寫處理循環狀態的方法，針對工具調用循環提供更具體的提示
     */
    @Override
    protected void handleStuckState() {
        String stuckPrompt;
        
        switch (getLoopDetectionCount()) {
            case 1:
                stuckPrompt = "注意：檢測到工具調用重複。請分析為什麼之前的工具調用沒有達到預期效果，並嘗試不同的工具或參數。";
                break;
            case 2:
                stuckPrompt = "警告：再次檢測到工具調用循環！請立即：\n" +
                             "1. 停止重複調用相同的工具\n" +
                             "2. 分析工具調用失敗的根本原因\n" +
                             "3. 選擇完全不同的工具或策略\n" +
                             "4. 如果問題無法解決，考慮使用terminate工具結束任務";
                // 清理工具調用歷史以減少模式重複
                if (toolCallHistory.size() > 6) {
                    toolCallHistory = new ArrayList<>(toolCallHistory.subList(toolCallHistory.size() - 3, toolCallHistory.size()));
                }
                break;
            default:
                stuckPrompt = "最後警告：持續的工具調用循環！必須立即：\n" +
                             "1. 承認當前工具策略無效\n" +
                             "2. 使用terminate工具結束任務\n" +
                             "3. 或提供根本性不同的解決方案\n" +
                             "禁止再次調用相同或相似的工具！";
                // 大幅清理歷史
                toolCallHistory.clear();
                break;
        }
        
        setNextStepPrompt(stuckPrompt + "\n" + (getNextStepPrompt() != null ? getNextStepPrompt() : ""));
        log.warn("ToolCallAgent stuck state handled with level " + getLoopDetectionCount() + " intervention");
        
        // 調用父類方法處理基礎邏輯
        super.handleStuckState();
    }
}
