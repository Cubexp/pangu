package com.pangu.framework.scheduler.impl;

/**
 * 任务拒绝异常
 * @author author
 */
public class TaskRejectedException extends RuntimeException {

	private static final long serialVersionUID = 6681519082476492615L;


	public TaskRejectedException() {
		super();
	}

	/**
	 * 创建一个任务拒绝异常
	 * @param message 异常信息
	 * @param cause 导致原因
	 */
	public TaskRejectedException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * 创建一个任务拒绝异常
	 * @param message 异常信息
	 */
	public TaskRejectedException(String message) {
		super(message);
	}

	/**
	 * 创建一个任务拒绝异常
	 * @param cause 导致原因
	 */
	public TaskRejectedException(Throwable cause) {
		super(cause);
	}

}
