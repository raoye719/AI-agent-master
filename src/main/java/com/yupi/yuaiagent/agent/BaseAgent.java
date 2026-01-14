package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.internal.StringUtil;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 抽象的基礎代理類，用於管理代碼狀態和執行流程
 *
 * 提供狀態轉換 内存管理和基於步驟的執行循環的基礎功能
 * 子類實現step方法
 */
@Data
@Slf4j
public abstract class BaseAgent {
    // 核心屬性
    private String name;

    // 提升
    // TODO 啥時候用到nextStepPrompt 具體場景
    private String systemPrompt;
    private String nextStepPrompt;

    // 狀態
    private AgentState state = AgentState.IDLE;

    // 執行控制
    private int maxSteps = 10;
    private int currentStep = 0;

    // llm
    private ChatClient chatClient;

    // Memory(自主維護會話上下文)
    private List<Message> messageList = new ArrayList<>();

    // 循環檢測相關屬性
    private int duplicateThreshold = 2;
    private List<String> stepResults = new ArrayList<>();
    
    // 循環嚴重程度跟踪
    private int loopDetectionCount = 0;
    private int maxLoopDetections = 3;

    /**
     * 運行代理
     * @param userPrompt 用戶提示詞
     * @return 執行結果
     */
    public String run(String userPrompt){
        if(this.state != AgentState.IDLE){
            throw new RuntimeException("Cannot run agent from state:" + this.state);
        }
        if(StringUtil.isBlank(userPrompt)){
            throw new RuntimeException("userPrompt is empty");
        }
        // 更改狀態
        state = AgentState.RUNNING;
        // 記錄消息上下文
        messageList.add(new UserMessage(userPrompt));
        
        // 保存結果列表
        List<String> results = new ArrayList<>();
        try {
            for(int i = 0; i < maxSteps; i++){
                int stepNUmber = i + 1;
                currentStep = stepNUmber;
                log.info("Executing step " + stepNUmber + "/" + maxSteps);
                // 單步執行
                String stepResult = step();
                String result = "Step" + stepNUmber + ":" + stepResult;
                results.add(result);
                
                // 記錄步驟結果用於循環檢測
                stepResults.add(stepResult);
                
                // 檢查是否陷入循環
                if (isStuck()) {
                    handleStuckState();
                    log.warn("Agent detected stuck state at step " + stepNUmber);
                }
                
                // 檢查是否已完成任務
                if (state == AgentState.FINISHED) {
                    log.info("Agent finished task at step " + stepNUmber);
                    break;
                }
            }
            // 檢查是否超出步驟限制
            if(currentStep >= maxSteps){
                state = AgentState.FINISHED;
                results.add("Terminated:Reached max steps:" + maxSteps);
            }
            // TODO 調用完一個工具后輸出還是調用完所有工具后輸出
            return String.join("\n", results);
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error(e.getMessage(), e);
            return "執行錯誤";
        } finally {
            this.cleanup();
        }
    }


    public SseEmitter runStream(String userPrompt){
        //創建SseEmitter
        SseEmitter emitter = new SseEmitter(300000L);
        CompletableFuture.runAsync(() -> {
            try {
                if(this.state != AgentState.IDLE){
                    emitter.send("錯誤 無法運行代理" + this.state);
                    emitter.complete();
                    return;
                }
                if(StringUtil.isBlank(userPrompt)){
                    emitter.send("prompt不能為空" + this.state);
                    emitter.complete();
                    return;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            // 更改狀態
            state = AgentState.RUNNING;
            // 記錄消息上下文
            messageList.add(new UserMessage(userPrompt));

            // 保存結果列表
            List<String> results = new ArrayList<>();
            try {
                for(int i = 0; i < maxSteps; i++){
                    int stepNUmber = i + 1;
                    currentStep = stepNUmber;
                    log.info("Executing step " + stepNUmber + "/" + maxSteps);
                    // 單步執行
                    String stepResult = step();
                    String result = "St" + stepNUmber + ":" + stepResult;
                    results.add(result);
                    emitter.send(result);
                    
                    // 記錄步驟結果用於循環檢測
                    stepResults.add(stepResult);
                    
                    // 檢查是否陷入循環
                    if (isStuck()) {
                        loopDetectionCount++;
                        log.warn("Agent detected stuck state at step " + stepNUmber + " (detection #" + loopDetectionCount + ")");
                        
                        if (loopDetectionCount >= maxLoopDetections) {
                            // 強制終止：多次檢測到循環後直接結束
                            log.error("Agent stuck in persistent loop. Forcing termination.");
                            emitter.send("錯誤：檢測到持續循環，強制終止任務");
                            state = AgentState.ERROR;
                            break;
                        } else {
                            handleStuckState();
                            emitter.send("警告：檢測到重複行為 (#" + loopDetectionCount + ")，正在調整策略...");
                        }
                    }
                    
                    // 檢查是否已完成任務
                    if (state == AgentState.FINISHED) {
                        log.info("Agent finished task at step " + stepNUmber);
                        emitter.send("任務完成");
                        break;
                    }
                }
                // 檢查是否超出步驟限制
                if(currentStep >= maxSteps){
                    state = AgentState.FINISHED;
                   emitter.send("Terminated:Reached max steps:" + maxSteps);
                }

            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error(e.getMessage(), e);
                try {
                    emitter.send("執行錯誤");
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(e);
                }
            } finally {
                this.cleanup();
            }
        });

        // 設置超時和完成回調
        emitter.onTimeout(() ->{
            state = AgentState.FINISHED;
            this.cleanup();
            log.warn("SEE connection timeout");
        });

        emitter.onCompletion(()->{
            if(state == AgentState.RUNNING){
                state = AgentState.FINISHED;
            }
            this.cleanup();
            log.warn("SEE connection complete");
        });

            return emitter;

    }

    /**
     * 清理資源
     */
    private void cleanup() {
        // 清理循環檢測相關數據
        stepResults.clear();
        loopDetectionCount = 0;
        // 重置狀態
        currentStep = 0;
    }

    /**
     * 執行步驟
     * @return 步驟執行結果
     */
    public abstract String step();

    /**
     * 處理陷入循環的狀態 - 渐进式干预策略
     */
    protected void handleStuckState() {
        String stuckPrompt;
        
        switch (loopDetectionCount) {
            case 1:
                // 第一次檢測：溫和提醒
                stuckPrompt = "注意：檢測到可能的重複響應。請考慮使用不同的方法或工具來解決問題。";
                break;
            case 2:
                // 第二次檢測：強烈建議改變策略
                stuckPrompt = "警告：再次檢測到重複行為！必須立即改變策略。請：\n" +
                             "1. 停止使用剛才失敗的方法\n" +
                             "2. 嘗試完全不同的工具或途徑\n" +
                             "3. 重新分析問題的根本原因";
                // 清理部分歷史以減少上下文污染
                if (stepResults.size() > 5) {
                    stepResults = stepResults.subList(stepResults.size() - 3, stepResults.size());
                }
                break;
            default:
                // 第三次及以上：最後警告
                stuckPrompt = "最後警告：持續檢測到循環行為！如果再次重複，任務將被強制終止。\n" +
                             "請立即：\n" +
                             "1. 承認當前方法無效\n" +
                             "2. 使用terminate工具結束任務\n" +
                             "3. 或提供完全不同的解決方案";
                // 大幅清理歷史
                stepResults.clear();
                break;
        }
        
        this.nextStepPrompt = stuckPrompt + "\n" + (this.nextStepPrompt != null ? this.nextStepPrompt : "");
        log.warn("Agent stuck state handled with level " + loopDetectionCount + " intervention");
    }

    /**
     * 檢查代理是否陷入循環
     * 基於步驟結果的重複性檢測
     *
     * @return 是否陷入循環
     */
    // 實際調用的是子類重寫的isstuck
    protected boolean isStuck() {
        if (stepResults.size() < duplicateThreshold + 1) {
            return false;
        }


        String lastResult = stepResults.get(stepResults.size() - 1);
        if (lastResult == null || lastResult.trim().isEmpty()) {
            return false;
        }

        // 計算重複內容出現次數
        int duplicateCount = 0;
        for (int i = stepResults.size() - 2; i >= 0; i--) {
            String result = stepResults.get(i);
            if (result != null && isSimilarResult(lastResult, result)) {
                duplicateCount++;
            }
        }

        return duplicateCount >= duplicateThreshold;
    }

    /**
     * 檢查兩個結果是否相似（考慮到可能的微小差異）
     *
     * @param result1 結果1
     * @param result2 結果2
     * @return 是否相似
     */
    protected boolean isSimilarResult(String result1, String result2) {
        if (result1 == null || result2 == null) {
            return false;
        }
        
        // 去除空白字符後比較
        String normalized1 = result1.trim().replaceAll("\\s+", " ");
        String normalized2 = result2.trim().replaceAll("\\s+", " ");
        
        // 完全相同
        if (normalized1.equals(normalized2)) {
            return true;
        }
        
        // 計算相似度（簡單的字符串相似度檢測）
        double similarity = calculateSimilarity(normalized1, normalized2);
        return similarity > 0.8; // 80%以上相似度認為是重複
    }

    /**
     * 計算兩個字符串的相似度
     *
     * @param s1 字符串1
     * @param s2 字符串2
     * @return 相似度（0-1之間）
     */
    private double calculateSimilarity(String s1, String s2) {
        if (s1.length() == 0 && s2.length() == 0) {
            return 1.0;
        }
        if (s1.length() == 0 || s2.length() == 0) {
            return 0.0;
        }
        
        int maxLength = Math.max(s1.length(), s2.length());
        int distance = levenshteinDistance(s1, s2);
        return 1.0 - (double) distance / maxLength;
    }

    /**
     * 計算編輯距離（Levenshtein距離）
     *
     * @param s1 字符串1
     * @param s2 字符串2
     * @return 編輯距離
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        
        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }
        
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = Math.min(Math.min(dp[i - 1][j], dp[i][j - 1]), dp[i - 1][j - 1]) + 1;
                }
            }
        }
        
        return dp[s1.length()][s2.length()];
    }

    // Getter方法供子類訪問
    protected int getLoopDetectionCount() {
        return loopDetectionCount;
    }

    protected String getNextStepPrompt() {
        return nextStepPrompt;
    }

    protected void setNextStepPrompt(String prompt) {
        this.nextStepPrompt = prompt;
    }

}
