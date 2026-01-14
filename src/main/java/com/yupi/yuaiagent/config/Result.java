package com.yupi.yuaiagent.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用返回結果
 * @param <T> 數據類型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {
    /**
     * 狀態碼
     */
    private Integer code;
    
    /**
     * 消息
     */
    private String message;
    
    /**
     * 數據
     */
    private T data;
    
    /**
     * 成功結果
     * @param data 數據
     * @return 結果對象
     * @param <T> 數據類型
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(0, "success", data);
    }
    
    /**
     * 成功結果（無數據）
     * @return 結果對象
     */
    public static Result<Void> success() {
        return new Result<>(0, "success", null);
    }
    
    /**
     * 錯誤結果
     * @param code 錯誤碼
     * @param message 錯誤消息
     * @return 結果對象
     */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }
}
