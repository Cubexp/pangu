package com.pangu.framework.resource.support;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;

import com.pangu.framework.utils.json.JsonUtils;

/**
 * 将json格式的array字符串转换成对应的数组实例
 * @author author
 */
public class JsonToCollectionConverter implements ConditionalGenericConverter {

	@Override
	public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
		if (sourceType.getType() != String.class) {
			return false;
		}
		return targetType.isCollection();
	}

	public Set<ConvertiblePair> getConvertibleTypes() {
		return Collections.singleton(new ConvertiblePair(String.class, Collection.class));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
		String content = (String) source;
		return JsonUtils.string2Collection(content, (Class<? extends Collection>) targetType.getType(), targetType.getElementTypeDescriptor().getType());
	}

}
