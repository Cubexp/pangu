package com.pangu.logic.module.battle.service.passive.param;

import com.pangu.logic.module.battle.service.skill.condition.ConditionType;
import com.pangu.framework.utils.json.JsonUtils;
import com.pangu.framework.utils.lang.NumberUtils;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

@Getter
public class ConditionallyReplaceSkillParam {

    private Map<ConditionType,String> strConditions;


    private Map<ConditionType, Object> realConditions;


    private String skillId;

    public Map<ConditionType, Object> getRealConditions() {
        if (realConditions != null) {
            return realConditions;
        }

        realConditions = new HashMap<>();
        for (Map.Entry<ConditionType, String> entry : strConditions.entrySet()) {
            final ConditionType key = entry.getKey();
            final String value = entry.getValue();

            Class<?> paramType = key.getParamType();
            if (paramType == String.class) {
                realConditions.put(key,value);
            }
            if (paramType.isEnum()) {
                realConditions.put(key,JsonUtils.convertObject(value, paramType));
            }
            if (paramType.isPrimitive()) {
                realConditions.put(key, NumberUtils.valueOf(paramType, value));
            }
            realConditions.put(key, JsonUtils.string2Object(value, paramType));
        }
        return realConditions;
    }
}
