package com.pangu.framework.resource.other;

import com.pangu.framework.utils.reflect.ReflectionUtils;
import com.pangu.framework.resource.anno.Id;
import com.pangu.framework.resource.anno.Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public class GetterBuilder {
	
	private static final Logger logger = LoggerFactory.getLogger(GetterBuilder.class);


	private static class FieldGetter implements Getter {
		
		private final Field field;
		
		public FieldGetter(Field field) {
			ReflectionUtils.makeAccessible(field);
			this.field = field;
		}
		
		@Override
		public Object getValue(Object object) {
			Object value = null;
			try {
				value = field.get(object);
			} catch (Exception e) {
				FormattingTuple message = MessageFormatter.format("标识符属性访问异常", e);
				logger.error(message.getMessage());
				throw new RuntimeException(message.getMessage());
			}
			return value;
		}
	}
	

	private static class MethodGetter implements Getter {
		
		private final Method method;
		
		public MethodGetter(Method method) {
			ReflectionUtils.makeAccessible(method);
			this.method = method;
		}
		
		@Override
		public Object getValue(Object object) {
			Object value = null;
			try {
				value = method.invoke(object);
			} catch (Exception e) {
				FormattingTuple message = MessageFormatter.format("标识方法访问异常", e);
				logger.error(message.getMessage());
				throw new RuntimeException(message.getMessage());
			}
			return value;
		}
	}
	
	/**
	 * 识别信息
	 * @author author
	 */
	private static class IdentityInfo {
		
		public final Field field;
		public final Method method;
		
		public IdentityInfo(Class<?> clz) {
			Field[] fields = ReflectionUtils.getDeclaredFieldsWith(clz, Id.class);
			if (fields.length > 1) {
				FormattingTuple message = MessageFormatter.format("类[{}]的属性唯一标识声明重复", clz);
				logger.error(message.getMessage());
				throw new RuntimeException(message.getMessage());
			}
			if (fields.length == 1) {
				this.field = fields[0];
				this.method = null;
				return;
			}
			Method[] methods = ReflectionUtils.getDeclaredGetMethodsWith(clz, Id.class);
			if (methods.length > 1) {
				FormattingTuple message = MessageFormatter.format("类[{}]的方法唯一标识声明重复", clz);
				logger.error(message.getMessage());
				throw new RuntimeException(message.getMessage());
			}
			if (methods.length == 1) {
				this.method = methods[0];
				this.field = null;
				return;
			}
			FormattingTuple message = MessageFormatter.format("类[{}]缺少唯一标识声明", clz);
			logger.error(message.getMessage());
			throw new RuntimeException(message.getMessage());
		}
		
		public boolean isField() {
			return field != null;
		}
	}

	/**
	 * 创建指定资源类的唯一标示获取实例
	 * @param clz 资源类
	 * @return
	 */
	public static Getter createIdGetter(Class<?> clz) {
		IdentityInfo info = new IdentityInfo(clz);
		Getter identifier = null;
		if (info.isField()) {
			identifier = new FieldGetter(info.field);
		} else {
			identifier = new MethodGetter(info.method);
		}
		return identifier;
	}
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static class FieldIndexGetter extends FieldGetter implements IndexGetter {
		
		private final String name;
		private final boolean unique;
		private final Comparator comparator;
		
		public FieldIndexGetter(Field field) {
			super(field);
			Index index = field.getAnnotation(Index.class);
			this.name = index.name();
			this.unique = index.unique();
			
			Class<Comparator> clz = (Class<Comparator>) index.comparatorClz();
			if (!clz.equals(Comparator.class)) {
				try {
					this.comparator = clz.newInstance();
				} catch (Exception e) {
					throw new IllegalArgumentException("索引比较器[" + clz.getName() + "]无法被实例化");
				}
			} else {
				comparator = null;
			}
		}
		
		@Override
		public String getName() {
			return name;
		}
		
		@Override
		public boolean isUnique() {
			return unique;
		}

		@Override
		public Comparator getComparator() {
			return comparator;
		}

		@Override
		public boolean hasComparator() {
			return comparator != null;
		}
	}
	
	/**
	 * 方法值索引值获取器
	 * @author author
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static class MethodIndexGetter extends MethodGetter implements IndexGetter {
		
		private final String name;
		private final boolean unique;
		private final Comparator comparator;
		
		public MethodIndexGetter(Method method) {
			super(method);
			Index index = method.getAnnotation(Index.class);
			this.name = index.name();
			this.unique = index.unique();
			
			Class<Comparator> clz = (Class<Comparator>) index.comparatorClz();
			if (!clz.equals(Comparator.class)) {
				try {
					this.comparator = clz.newInstance();
				} catch (Exception e) {
					throw new IllegalArgumentException("索引比较器[" + clz.getName() + "]无法被实例化");
				}
			} else {
				comparator = null;
			}
		}
		
		@Override
		public String getName() {
			return name;
		}
		
		@Override
		public boolean isUnique() {
			return unique;
		}

		@Override
		public Comparator getComparator() {
			return comparator;
		}

		@Override
		public boolean hasComparator() {
			return comparator != null;
		}
	}

	/**
	 * 创建资源索引
	 * @param clz
	 * @return
	 */
	public static Map<String, IndexGetter> createIndexGetters(Class<?> clz) {
		Field[] fields = ReflectionUtils.getDeclaredFieldsWith(clz, Index.class);
		Method[] methods = ReflectionUtils.getDeclaredGetMethodsWith(clz, Index.class);
		
		List<IndexGetter> getters = new ArrayList<IndexGetter>(fields.length + methods.length);
		for (Field field : fields) {
			IndexGetter getter = new FieldIndexGetter(field);
			getters.add(getter);
		}
		for (Method method : methods) {
			IndexGetter getter = new MethodIndexGetter(method);
			getters.add(getter);
		}

		Map<String, IndexGetter> result = new HashMap<String, IndexGetter>(getters.size());
		for (IndexGetter getter : getters) {
			String name = getter.getName();
			if (result.put(name, getter) != null) {
				FormattingTuple message = MessageFormatter.format("资源类[{}]的索引名[{}]重复", clz, name);
				logger.error(message.getMessage());
				throw new RuntimeException(message.getMessage());
			}
		}
		return result;
	}
}
