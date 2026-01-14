package com.yupi.yuaiagent.agent.model;

/**
 * 代理執行狀態的枚舉類
 */
public enum AgentState {
    /**
     *
     */
    IDLE,

    /**
     * 運行中狀態
     */
    RUNNING,

    /**
     * 已完成狀態
     */
    FINISHED,

    /**
     * 錯誤狀態
     */
    ERROR

}
