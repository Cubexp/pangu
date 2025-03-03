package com.pangu.framework.protocol.exception;

/**
 * 未定义的传输对象类型
 * 
 * @author author
 *
 */
public class UnknowTypeDefException extends RuntimeException{
	private static final long serialVersionUID = 4895297111772939299L;

	public UnknowTypeDefException(int rawType) {
		super("未定义的传输对象类型[" + rawType + "]");
	}

	public UnknowTypeDefException(Class<? extends Object> clz) {
		super("未定义的传输对象类型[" + clz.getName() + "]");
	}
	
}
