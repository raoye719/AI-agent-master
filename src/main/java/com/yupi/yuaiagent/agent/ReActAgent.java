package com.yupi.yuaiagent.agent;

import lombok.EqualsAndHashCode;

/**
 * ReAct(Reasoning and Acting)模式的代理抽象類
 * 思考-行動的循環模式
 */
@EqualsAndHashCode(callSuper=true) // TODO 這個什麽注釋
public abstract class ReActAgent extends BaseAgent{

    /**
     * 處理當前狀態並決定下一步行動
     * @return 是否需要執行行動 ture需要 false不需要
     */
    public abstract boolean think();

    /**
     * 執行決定的行動
     * @return 行動執行結果
     */
    // TODO 返回值
    public abstract String act();


    /**
     * 執行單個步驟
     * @return 步驟執行結果
     */
    @Override
    public String step() {
        try {
            boolean result = think();
            if(result){
                return act();
            }
            return "思考完成 無需行動";
        } catch (Exception e) {
            return "出錯：" + e.getMessage();
        }
    }
}
