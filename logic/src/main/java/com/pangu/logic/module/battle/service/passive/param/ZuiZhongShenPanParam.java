package com.pangu.logic.module.battle.service.passive.param;

import com.pangu.logic.module.battle.model.UnitState;
import lombok.Getter;

@Getter
public class ZuiZhongShenPanParam {
    //dot_debuff_id
    private String deBuffId;
    //作为dot伤害的比例
    private double dmgFactor;
    //能触发该被动的主动技能标签
    private String skillTag;

    
    private double condition;
    
    private String delayBuffTag;
    
    private BuffUpdateParam buff;
    
    private UnitState unitState = UnitState.MAGIC_IMMUNE;
}
