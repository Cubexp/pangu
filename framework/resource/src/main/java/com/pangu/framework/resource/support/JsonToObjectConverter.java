package com.pangu.framework.resource.support;

import java.util.Collections;
import java.util.Set;

import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;

import com.pangu.framework.resource.JsonObject;
import com.pangu.framework.utils.json.JsonUtils;

/**
 * 将json格式的array字符串转换成对应的数组实例
 * @author author
 */
public class JsonToObjectConverter implements ConditionalGenericConverter {

	@Override
	public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
		if (sourceType.getType() != String.class) {
			return false;
		}
		return JsonObject.class.isAssignableFrom(targetType.getType());
	}

	@Override
	public Set<ConvertiblePair> getConvertibleTypes() {
		return Collections.singleton(new ConvertiblePair(String.class, Object.class));
	}

	@Override
	public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
		String content = (String) source;
		if (targetType.isArray()) {
			return JsonUtils.string2Array(content, targetType.getElementTypeDescriptor().getType());
		} else {
			return JsonUtils.string2Object(content, targetType.getObjectType());
		}
	}

}
