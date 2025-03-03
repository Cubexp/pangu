package com.pangu.framework.resource.other;

import com.pangu.framework.utils.reflect.ReflectionUtils;
import com.pangu.framework.resource.Validate;
import com.pangu.framework.resource.anno.InjectBean;
import com.pangu.framework.resource.anno.Resource;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 资源定义信息对象
 * @author author
 */
public class ResourceDefinition {

	public final static String FILE_SPLIT = ".";
	public final static String FILE_PATH = File.separator;


	private final static ReflectionUtils.FieldFilter INJECT_FILTER = new ReflectionUtils.FieldFilter() {
		@Override
		public boolean matches(Field field) {
            return field.isAnnotationPresent(InjectBean.class);
        }
	};


	private final Class<?> clz;

	private final String location;

	private String resolverLocation;

	private final String path;

	private final FormatDefinition format;

	private final Set<InjectDefinition> injects = new HashSet<InjectDefinition>();


	public ResourceDefinition(Class<?> clz, FormatDefinition format) {
		this.clz = clz;
		this.format = format;
		this.location = format.getLocation();
		Resource anno = clz.getAnnotation(Resource.class);
		StringBuilder builder = new StringBuilder();
		// builder.append(format.getLocation());
		// builder.append(FILE_PATH);
		if (ArrayUtils.isNotEmpty(anno.value())) {
			String[] dirs = anno.value();
			StringJoiner joiner = new StringJoiner(FILE_PATH);
			for (CharSequence path : dirs) {
				if (StringUtils.isBlank(path)) {
					continue;
				}
				joiner.add(path);
			}
			String dir = joiner.toString();
			if (StringUtils.isNotEmpty(dir)) {
				int start = 0;
				int end = dir.length();
				if (StringUtils.startsWith(dir, FILE_PATH)) {
					start++;
				}
				if (StringUtils.endsWith(dir, FILE_PATH)) {
					end--;
				}
				builder.append(dir, start, end).append(FILE_PATH);
			}
		}
		builder.append(clz.getSimpleName()).append(FILE_SPLIT).append(format.getSuffix());
		this.path = builder.toString();
		ReflectionUtils.doWithDeclaredFields(clz, new ReflectionUtils.FieldCallback() {
			@Override
			public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
				InjectDefinition definition = new InjectDefinition(field);
				injects.add(definition);
			}
		}, INJECT_FILTER);
	}

	/**
	 * 获取静态属性注入定义
	 * @return
	 */
	public Set<InjectDefinition> getStaticInjects() {
		HashSet<InjectDefinition> result = new HashSet<InjectDefinition>();
		for (InjectDefinition definition : this.injects) {
			Field field = definition.getField();
			if (Modifier.isStatic(field.getModifiers())) {
				result.add(definition);
			}
		}
		return result;
	}

	/**
	 * 获取非静态属性注入定义
	 * @return
	 */
	public Set<InjectDefinition> getInjects() {
		HashSet<InjectDefinition> result = new HashSet<InjectDefinition>();
		for (InjectDefinition definition : this.injects) {
			Field field = definition.getField();
			if (!Modifier.isStatic(field.getModifiers())) {
				result.add(definition);
			}
		}
		return result;
	}

	/**
	 * 资源是否需要校验
	 * @return
	 */
	public boolean isNeedValidate() {
        return Validate.class.isAssignableFrom(clz);
    }

	/**
	 * @param location
	 */
	public void resolveLocation(String location) {
		this.resolverLocation = location;
	}

	// Getter and Setter ...

	public Class<?> getClz() {
		return clz;
	}


	public FormatDefinition getFormat() {
		return format;
	}

	public String getLocation() {
		if (resolverLocation != null) {
			return resolverLocation;
		}
		return location;
	}
	
	public String getPath() {
		return path;
	}

	@Override
	public String toString() {
		return ReflectionToStringBuilder.toString(this);
	}
}
