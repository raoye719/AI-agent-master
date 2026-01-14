# 智能體循環檢測功能文檔

## 概述

智能體循環檢測功能旨在防止AI代理陷入無限循環，通過多層次檢測機制和**渐进式干预策略**，確保代理能夠有效執行任務而不會浪費計算資源。

## 功能特性

### 1. 多層次循環檢測
- **基礎步驟檢測**：檢測連續步驟中的重複響應模式
- **工具調用檢測**：專門檢測工具調用的重複模式
- **相似度分析**：使用編輯距離算法進行智能相似度判斷

### 2. 渐进式干预機制 ⭐ **新功能**
- **第1次檢測**：溫和提醒，建議改變方法
- **第2次檢測**：強烈警告，清理部分歷史記錄
- **第3次檢測**：最後警告，大幅清理歷史記錄
- **第4次檢測**：強制終止任務，防止無限循環

### 3. 上下文污染處理 ⭐ **新功能**
- **漸進式歷史清理**：根據循環嚴重程度清理不同量的歷史
- **模式中斷**：破壞已形成的重複模式
- **記憶重置**：在嚴重情況下重置代理記憶

### 4. 可配置參數
- `duplicateThreshold`：重複檢測閾值（默認：2）
- `maxSteps`：最大執行步數
- `maxLoopDetections`：最大循環檢測次數（默認：3）

## 使用方法

### 基本配置

```java
// 在BaseAgent中設置參數
private int duplicateThreshold = 2;        // 重複檢測閾值
private int maxLoopDetections = 3;         // 最大循環檢測次數

// 創建代理實例
YuManus agent = new YuManus();
// 循環檢測會自動啟用
```

### 自定義循環檢測邏輯

```java
public class CustomAgent extends BaseAgent {
    
    @Override
    protected boolean isStuck() {
        // 自定義循環檢測邏輯
        return super.isStuck() || customLoopDetection();
    }
    
    @Override
    protected void handleStuckState() {
        // 自定義處理邏輯
        if (getLoopDetectionCount() >= 2) {
            // 執行特殊處理
            performSpecialIntervention();
        }
        super.handleStuckState();
    }
    
    private boolean customLoopDetection() {
        // 實現特定的循環檢測邏輯
        return false;
    }
    
    private void performSpecialIntervention() {
        // 實現特殊干預措施
    }
}
```

## 檢測機制

### BaseAgent循環檢測

1. **步驟結果記錄**：每個執行步驟的結果都會被記錄
2. **相似度計算**：使用編輯距離算法計算結果相似度
3. **重複模式識別**：當相似結果超過閾值時觸發檢測
4. **渐进式干预**：根據檢測次數應用不同強度的干預

### ToolCallAgent工具調用檢測

1. **工具調用記錄**：記錄每次工具調用的名稱和參數
2. **調用模式分析**：檢測相同或相似的工具調用模式
3. **專門化處理**：針對工具調用循環的特殊處理邏輯
4. **歷史清理**：清理工具調用歷史以防止模式重複

## 渐进式干预策略 ⭐ **核心改進**

### 第1次檢測：溫和提醒
```
注意：檢測到可能的重複響應。請考慮使用不同的方法或工具來解決問題。
```

### 第2次檢測：強烈警告
```
警告：再次檢測到重複行為！必須立即改變策略。請：
1. 停止使用剛才失敗的方法
2. 嘗試完全不同的工具或途徑  
3. 重新分析問題的根本原因
```
- 同時清理部分歷史記錄（保留最近3條）

### 第3次檢測：最後警告
```
最後警告：持續檢測到循環行為！如果再次重複，任務將被強制終止。
請立即：
1. 承認當前方法無效
2. 使用terminate工具結束任務
3. 或提供完全不同的解決方案
```
- 大幅清理歷史記錄

### 第4次檢測：強制終止
- 直接設置代理狀態為ERROR
- 終止任務執行
- 記錄強制終止日誌

## 處理策略

### 自動處理機制

1. **提示注入**：向代理注入反循環提示
2. **歷史清理**：清理重複的歷史記錄
3. **狀態重置**：在必要時重置代理狀態
4. **強制終止**：在持續循環時強制結束任務

### 手動干預選項

1. **調整閾值**：根據任務特性調整檢測敏感度
2. **自定義處理**：實現特定的循環處理邏輯
3. **監控日誌**：通過日誌監控循環檢測情況

## 配置建議

### 一般任務
```java
duplicateThreshold = 2;     // 檢測2次重複即觸發
maxLoopDetections = 3;      // 最多允許3次循環檢測
maxSteps = 10;              // 最大執行10步
```

### 複雜任務
```java
duplicateThreshold = 3;     // 提高容忍度
maxLoopDetections = 4;      // 允許更多檢測次數
maxSteps = 20;              // 增加最大步數
```

### 簡單任務
```java
duplicateThreshold = 1;     // 降低容忍度
maxLoopDetections = 2;      // 快速終止
maxSteps = 5;               // 限制步數
```

## 監控和調試

### 日誌監控

```java
// 循環檢測日誌
log.warn("Agent detected stuck state at step {} (detection #{})", stepNumber, loopDetectionCount);

// 干預措施日誌  
log.warn("Agent stuck state handled with level {} intervention", loopDetectionCount);

// 強制終止日誌
log.error("Agent stuck in persistent loop. Forcing termination.");
```

### 調試信息

1. **檢測計數**：`getLoopDetectionCount()` 獲取當前檢測次數
2. **歷史記錄**：查看 `stepResults` 和 `toolCallHistory`
3. **相似度分析**：檢查 `calculateSimilarity()` 結果

## 最佳實踐

### 1. 合理設置閾值
- 根據任務複雜度調整 `duplicateThreshold`
- 考慮任務特性設置 `maxLoopDetections`

### 2. 監控循環檢測
- 定期檢查循環檢測日誌
- 分析循環發生的原因

### 3. 優化提示詞
- 在系統提示中明確說明避免重複
- 提供明確的任務完成標準

### 4. 工具設計
- 確保工具有明確的成功/失敗反饋
- 避免工具返回模糊的結果

### 5. 測試驗證
- 使用測試用例驗證循環檢測功能
- 模擬各種循環場景

## 注意事項

### 1. 性能影響
- 循環檢測會增加少量計算開銷
- 歷史記錄清理有助於控制記憶體使用

### 2. 誤檢風險
- 過低的閾值可能導致誤檢
- 需要根據實際情況調整參數

### 3. 上下文丟失
- 歷史清理可能導致上下文信息丟失
- 需要平衡循環檢測和上下文保持

### 4. 大模型限制
- 某些情況下大模型可能仍然重複
- 強制終止機制提供最後保障

## 相關文件

- `BaseAgent.java` - 基礎循環檢測實現
- `ToolCallAgent.java` - 工具調用循環檢測
- `LoopDetectionTest.java` - 測試用例
- `LoopDetectionExample.java` - 使用示例
- `ImprovedLoopDetectionExample.java` - 改進機制演示 ⭐ **新增**

## 更新日誌

### v2.0 - 渐进式干预機制
- ✅ 新增渐进式干预策略
- ✅ 新增上下文污染處理
- ✅ 新增強制終止機制
- ✅ 改進工具調用循環檢測
- ✅ 新增循環嚴重程度跟踪

### v1.0 - 基礎循環檢測
- ✅ 基礎步驟重複檢測
- ✅ 工具調用重複檢測  
- ✅ 編輯距離相似度算法
- ✅ 可配置檢測參數