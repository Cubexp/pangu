package com.pangu.framework.scheduler.impl;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 修改的 ThreadPoolExecutor
 * @see ThreadPoolExecutor
 * @author author
 */
public class FixThreadPoolExecutor extends AbstractExecutorService {

	/**
	 * Permission for checking shutdown
	 */
	private static final RuntimePermission shutdownPerm = new RuntimePermission("modifyThread");

	volatile int runState;
	static final int RUNNING = 0;
	static final int SHUTDOWN = 1;
	static final int STOP = 2;
	static final int TERMINATED = 3;

	/**
	 * The queue used for holding tasks and handing off to worker threads. Note
	 * that when using this queue, we do not require that workQueue.poll()
	 * returning null necessarily means that workQueue.isEmpty(), so must
	 * sometimes check both. This accommodates special-purpose queues such as
	 * DelayQueues for which poll() is allowed to return null even if it may
	 * later return non-null when delays expire.
	 */
	private final BlockingQueue<Runnable> workQueue;

	/**
	 * Lock held on updates to poolSize, corePoolSize, maximumPoolSize,
	 * runState, and workers set.
	 */
	private final ReentrantLock mainLock = new ReentrantLock();

	/**
	 * Wait condition to support awaitTermination
	 */
	private final Condition termination = mainLock.newCondition();

	/**
	 * Set containing all worker threads in pool. Accessed only when holding
	 * mainLock.
	 */
	private final HashSet<Worker> workers = new HashSet<Worker>();

	/**
	 * Timeout in nanoseconds for idle threads waiting for work. Threads use
	 * this timeout when there are more than corePoolSize present or if
	 * allowCoreThreadTimeOut. Otherwise they wait forever for new work.
	 */
	private volatile long keepAliveTime;

	/**
	 * If false (default) core threads stay alive even when idle. If true, core
	 * threads use keepAliveTime to time out waiting for work.
	 */
	private volatile boolean allowCoreThreadTimeOut;

	/**
	 * Core pool size, updated only while holding mainLock, but volatile to
	 * allow concurrent readability even during updates.
	 */
	private volatile int corePoolSize;

	/**
	 * Maximum pool size, updated only while holding mainLock but volatile to
	 * allow concurrent readability even during updates.
	 */
	private volatile int maximumPoolSize;

	/**
	 * Current pool size, updated only while holding mainLock but volatile to
	 * allow concurrent readability even during updates.
	 */
	private volatile int poolSize;

	/**
	 * Handler called when saturated or shutdown in execute.
	 */
	private volatile RejectedExecutionHandler handler;

	/**
	 * Factory for new threads. All threads are created using this factory (via
	 * method addThread). All callers must be prepared for addThread to fail by
	 * returning null, which may reflect a system or user's policy limiting the
	 * number of threads. Even though it is not treated as an error, failure to
	 * create threads may result in new tasks being rejected or existing ones
	 * remaining stuck in the queue. On the other hand, no special precautions
	 * exist to handle OutOfMemoryErrors that might be thrown while trying to
	 * create threads, since there is generally no recourse from within this
	 * class.
	 */
	private volatile ThreadFactory threadFactory;

	/**
	 * Tracks largest attained pool size.
	 */
	private int largestPoolSize;

	/**
	 * Counter for completed tasks. Updated only on termination of worker
	 * threads.
	 */
	private long completedTaskCount;

	/**
	 * The default rejected execution handler
	 */
	private static final RejectedExecutionHandler defaultHandler = new AbortPolicy();

	// Constructors

	/**
	 * Creates a new <tt>ThreadPoolExecutor</tt> with the given initial
	 * parameters and default thread factory and rejected execution handler. It
	 * may be more convenient to use one of the {@link Executors} factory
	 * methods instead of this general purpose constructor.
	 * @param corePoolSize the number of threads to keep in the pool, even if
	 * they are idle.
	 * @param maximumPoolSize the maximum number of threads to allow in the
	 * pool.
	 * @param keepAliveTime when the number of threads is greater than the core,
	 * this is the maximum time that excess idle threads will wait for new tasks
	 * before terminating.
	 * @param unit the time unit for the keepAliveTime argument.
	 * @param workQueue the queue to use for holding tasks before they are
	 * executed. This queue will hold only the <tt>Runnable</tt> tasks submitted
	 * by the <tt>execute</tt> method.
	 * @throws IllegalArgumentException if corePoolSize or keepAliveTime less
	 * than zero, or if maximumPoolSize less than or equal to zero, or if
	 * corePoolSize greater than maximumPoolSize.
	 * @throws NullPointerException if <tt>workQueue</tt> is null
	 */
	public FixThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime,
			TimeUnit unit, BlockingQueue<Runnable> workQueue) {
		this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, Executors
				.defaultThreadFactory(), defaultHandler);
	}

	/**
	 * Creates a new <tt>ThreadPoolExecutor</tt> with the given initial
	 * parameters and default rejected execution handler.
	 * @param corePoolSize the number of threads to keep in the pool, even if
	 * they are idle.
	 * @param maximumPoolSize the maximum number of threads to allow in the
	 * pool.
	 * @param keepAliveTime when the number of threads is greater than the core,
	 * this is the maximum time that excess idle threads will wait for new tasks
	 * before terminating.
	 * @param unit the time unit for the keepAliveTime argument.
	 * @param workQueue the queue to use for holding tasks before they are
	 * executed. This queue will hold only the <tt>Runnable</tt> tasks submitted
	 * by the <tt>execute</tt> method.
	 * @param threadFactory the factory to use when the executor creates a new
	 * thread.
	 * @throws IllegalArgumentException if corePoolSize or keepAliveTime less
	 * than zero, or if maximumPoolSize less than or equal to zero, or if
	 * corePoolSize greater than maximumPoolSize.
	 * @throws NullPointerException if <tt>workQueue</tt> or
	 * <tt>threadFactory</tt> are null.
	 */
	public FixThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime,
			TimeUnit unit, BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
		this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory,
				defaultHandler);
	}

	/**
	 * Creates a new <tt>ThreadPoolExecutor</tt> with the given initial
	 * parameters and default thread factory.
	 * @param corePoolSize the number of threads to keep in the pool, even if
	 * they are idle.
	 * @param maximumPoolSize the maximum number of threads to allow in the
	 * pool.
	 * @param keepAliveTime when the number of threads is greater than the core,
	 * this is the maximum time that excess idle threads will wait for new tasks
	 * before terminating.
	 * @param unit the time unit for the keepAliveTime argument.
	 * @param workQueue the queue to use for holding tasks before they are
	 * executed. This queue will hold only the <tt>Runnable</tt> tasks submitted
	 * by the <tt>execute</tt> method.
	 * @param handler the handler to use when execution is blocked because the
	 * thread bounds and queue capacities are reached.
	 * @throws IllegalArgumentException if corePoolSize or keepAliveTime less
	 * than zero, or if maximumPoolSize less than or equal to zero, or if
	 * corePoolSize greater than maximumPoolSize.
	 * @throws NullPointerException if <tt>workQueue</tt> or <tt>handler</tt>
	 * are null.
	 */
	public FixThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime,
			TimeUnit unit, BlockingQueue<Runnable> workQueue, RejectedExecutionHandler handler) {
		this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, Executors
				.defaultThreadFactory(), handler);
	}

	/**
	 * Creates a new <tt>ThreadPoolExecutor</tt> with the given initial
	 * parameters.
	 * @param corePoolSize the number of threads to keep in the pool, even if
	 * they are idle.
	 * @param maximumPoolSize the maximum number of threads to allow in the
	 * pool.
	 * @param keepAliveTime when the number of threads is greater than the core,
	 * this is the maximum time that excess idle threads will wait for new tasks
	 * before terminating.
	 * @param unit the time unit for the keepAliveTime argument.
	 * @param workQueue the queue to use for holding tasks before they are
	 * executed. This queue will hold only the <tt>Runnable</tt> tasks submitted
	 * by the <tt>execute</tt> method.
	 * @param threadFactory the factory to use when the executor creates a new
	 * thread.
	 * @param handler the handler to use when execution is blocked because the
	 * thread bounds and queue capacities are reached.
	 * @throws IllegalArgumentException if corePoolSize or keepAliveTime less
	 * than zero, or if maximumPoolSize less than or equal to zero, or if
	 * corePoolSize greater than maximumPoolSize.
	 * @throws NullPointerException if <tt>workQueue</tt> or
	 * <tt>threadFactory</tt> or <tt>handler</tt> are null.
	 */
	public FixThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime,
			TimeUnit unit, BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory,
			RejectedExecutionHandler handler) {
		if (corePoolSize < 0 || maximumPoolSize <= 0 || maximumPoolSize < corePoolSize
				|| keepAliveTime < 0)
			throw new IllegalArgumentException();
		if (workQueue == null || threadFactory == null || handler == null)
			throw new NullPointerException();
		this.corePoolSize = corePoolSize;
		this.maximumPoolSize = maximumPoolSize;
		this.workQueue = workQueue;
		this.keepAliveTime = unit.toNanos(keepAliveTime);
		this.threadFactory = threadFactory;
		this.handler = handler;
	}

	/* Support for execute(). Method execute() and its helper methods handle the
	 * various cases encountered when new tasks are submitted. The main
	 * execute() method proceeds in 3 steps: 1. If it appears that fewer than
	 * corePoolSize threads are running, try to start a new thread with the
	 * given command as its first task. The check here errs on the side of
	 * caution. The call to addIfUnderCorePoolSize rechecks runState and pool
	 * size under lock (they change only under lock) so prevents false alarms
	 * that would add threads when it shouldn't, but may also fail to add them
	 * when they should. This is compensated within the following steps. 2. If a
	 * task can be successfully queued, then we are done, but still need to
	 * compensate for missing the fact that we should have added a thread
	 * (because existing ones died) or that shutdown occurred since entry into
	 * this method. So we recheck state and if necessary (in
	 * ensureQueuedTaskHandled) roll back the enqueuing if shut down, or start a
	 * new thread if there are none. 3. If we cannot queue task, then we try to
	 * add a new thread. There's no guesswork here (addIfUnderMaximumPoolSize)
	 * since it is performed under lock. If it fails, we know we are shut down
	 * or saturated. The reason for taking this overall approach is to normally
	 * avoid holding mainLock during this method, which would be a serious
	 * scalability bottleneck. After warmup, almost all calls take step 2 in a
	 * way that entails no locking. */

	/**
	 * Executes the given task sometime in the future. The task may execute in a
	 * new thread or in an existing pooled thread. If the task cannot be
	 * submitted for execution, either because this executor has been shutdown
	 * or because its capacity has been reached, the task is handled by the
	 * current <tt>RejectedExecutionHandler</tt>.
	 * @param command the task to execute
	 * @throws RejectedExecutionException at discretion of
	 * <tt>RejectedExecutionHandler</tt>, if task cannot be accepted for
	 * execution
	 * @throws NullPointerException if command is null
	 */
	public void execute(Runnable command) {
		if (command == null)
			throw new NullPointerException();
		if (poolSize >= corePoolSize || !addIfUnderCorePoolSize(command)) {
			if (runState == RUNNING && workQueue.offer(command)) {
				if (runState != RUNNING || poolSize == 0)
					ensureQueuedTaskHandled(command);
			} else if (!addIfUnderMaximumPoolSize(command))
				reject(command); // is shutdown or saturated
		}
	}

	/**
	 * Creates and returns a new thread running firstTask as its first task.
	 * Call only while holding mainLock.
	 * @param firstTask the task the new thread should run first (or null if
	 * none)
	 * @return the new thread, or null if threadFactory fails to create thread
	 */
	private Thread addThread(Runnable firstTask) {
		Worker w = new Worker(firstTask);
		Thread t = threadFactory.newThread(w);
		if (t != null) {
			w.thread = t;
			workers.add(w);
			int nt = ++poolSize;
			if (nt > largestPoolSize)
				largestPoolSize = nt;
		}
		return t;
	}

	/**
	 * Creates and starts a new thread running firstTask as its first task, only
	 * if fewer than corePoolSize threads are running and the pool is not shut
	 * down.
	 * @param firstTask the task the new thread should run first (or null if
	 * none)
	 * @return true if successful
	 */
	private boolean addIfUnderCorePoolSize(Runnable firstTask) {
		Thread t = null;
		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		try {
			if (poolSize < corePoolSize && runState == RUNNING)
				t = addThread(firstTask);
		} finally {
			mainLock.unlock();
		}
		if (t == null)
			return false;
		t.start();
		return true;
	}

	/**
	 * Creates and starts a new thread running firstTask as its first task, only
	 * if fewer than maximumPoolSize threads are running and pool is not shut
	 * down.
	 * @param firstTask the task the new thread should run first (or null if
	 * none)
	 * @return true if successful
	 */
	private boolean addIfUnderMaximumPoolSize(Runnable firstTask) {
		Thread t = null;
		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		try {
			if (poolSize < maximumPoolSize && runState == RUNNING)
				t = addThread(firstTask);
		} finally {
			mainLock.unlock();
		}
		if (t == null)
			return false;
		t.start();
		return true;
	}

	/**
	 * Rechecks state after queuing a task. Called from execute when pool state
	 * has been observed to change after queuing a task. If the task was queued
	 * concurrently with a call to shutdownNow, and is still present in the
	 * queue, this task must be removed and rejected to preserve shutdownNow
	 * guarantees. Otherwise, this method ensures (unless addThread fails) that
	 * there is at least one live thread to handle this task
	 * @param command the task
	 */
	private void ensureQueuedTaskHandled(Runnable command) {
		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		boolean reject = false;
		Thread t = null;
		try {
			int state = runState;
			if (state != RUNNING && workQueue.remove(command))
				reject = true;
			else if (state < STOP && poolSize < Math.max(corePoolSize, 1) && !workQueue.isEmpty())
				t = addThread(null);
		} finally {
			mainLock.unlock();
		}
		if (reject)
			reject(command);
		else if (t != null)
			t.start();
	}

	/**
	 * Invokes the rejected execution handler for the given command.
	 */
	void reject(Runnable command) {
		handler.rejectedExecution(command, this);
	}

	/**
	 * Worker threads. Worker threads can start out life either with an initial
	 * first task, or without one. Normally, they are started with a first task.
	 * This enables execute(), etc to bypass queuing when there are fewer than
	 * corePoolSize threads (in which case we always start one), or when the
	 * queue is full.(in which case we must bypass queue.) Initially idle
	 * threads are created either by users (prestartCoreThread and
	 * setCorePoolSize) or when methods ensureQueuedTaskHandled and tryTerminate
	 * notice that the queue is not empty but there are no active threads to
	 * handle them. After completing a task, workers try to get another one, via
	 * method getTask. If they cannot (i.e., getTask returns null), they exit,
	 * calling workerDone to update pool state. When starting to run a task,
	 * unless the pool is stopped, each worker thread ensures that it is not
	 * interrupted, and uses runLock to prevent the pool from interrupting it in
	 * the midst of execution. This shields user tasks from any interrupts that
	 * may otherwise be needed during shutdown (see method
	 * interruptIdleWorkers), unless the pool is stopping (via shutdownNow) in
	 * which case interrupts are let through to affect both tasks and workers.
	 * However, this shielding does not necessarily protect the workers from
	 * lagging interrupts from other user threads directed towards tasks that
	 * have already been completed. Thus, a worker thread may be interrupted
	 * needlessly (for example in getTask), in which case it rechecks pool state
	 * to see if it should exit.
	 */
	private final class Worker implements Runnable {
		/**
		 * The runLock is acquired and released surrounding each task execution.
		 * It mainly protects against interrupts that are intended to cancel the
		 * worker thread from instead interrupting the task being run.
		 */
		private final ReentrantLock runLock = new ReentrantLock();

		/**
		 * Initial task to run before entering run loop. Possibly null.
		 */
		private Runnable firstTask;

		/**
		 * Per thread completed task counter; accumulated into
		 * completedTaskCount upon termination.
		 */
		volatile long completedTasks;

		/**
		 * Thread this worker is running in. Acts as a final field, but cannot
		 * be set until thread is created.
		 */
		Thread thread;

		Worker(Runnable firstTask) {
			this.firstTask = firstTask;
		}

		boolean isActive() {
			return runLock.isLocked();
		}

		/**
		 * Interrupts thread if not running a task.
		 */
		void interruptIfIdle() {
			final ReentrantLock runLock = this.runLock;
			if (runLock.tryLock()) {
				try {
					if (thread != Thread.currentThread())
						thread.interrupt();
				} finally {
					runLock.unlock();
				}
			}
		}

		/**
		 * Interrupts thread even if running a task.
		 */
		void interruptNow() {
			thread.interrupt();
		}

		/**
		 * Runs a single task between before/after methods.
		 */
		private void runTask(Runnable task) {
			final ReentrantLock runLock = this.runLock;
			runLock.lock();
			try {
				/* Ensure that unless pool is stopping, this thread does not
				 * have its interrupt set. This requires a double-check of state
				 * in case the interrupt was cleared concurrently with a
				 * shutdownNow -- if so, the interrupt is re-enabled. */
				if (runState < STOP && Thread.interrupted() && runState >= STOP)
					thread.interrupt();
				/* Track execution state to ensure that afterExecute is called
				 * only if task completed or threw exception. Otherwise, the
				 * caught runtime exception will have been thrown by
				 * afterExecute itself, in which case we don't want to call it
				 * again. */
				boolean ran = false;
				beforeExecute(thread, task);
				try {
					task.run();
					ran = true;
					afterExecute(task, null);
					++completedTasks;
				} catch (RuntimeException ex) {
					if (!ran)
						afterExecute(task, ex);
					throw ex;
				}
			} finally {
				runLock.unlock();
			}
		}

		/**
		 * Main run loop
		 */
		public void run() {
			try {
				Runnable task = firstTask;
				firstTask = null;
				while (task != null || (task = getTask()) != null) {
					runTask(task);
					task = null;
				}
			} finally {
				workerDone(this);
			}
		}
	}



	/**
	 * Gets the next task for a worker thread to run. The general approach is
	 * similar to execute() in that worker threads trying to get a task to run
	 * do so on the basis of prevailing state accessed outside of locks. This
	 * may cause them to choose the "wrong" action, such as trying to exit
	 * because no tasks appear to be available, or entering a take when the pool
	 * is in the process of being shut down. These potential problems are
	 * countered by (1) rechecking pool state (in workerCanExit) before giving
	 * up, and (2) interrupting other workers upon shutdown, so they can recheck
	 * state. All other user-based state changes (to allowCoreThreadTimeOut etc)
	 * are OK even when performed asynchronously wrt getTask.
	 * @return the task
	 */
	Runnable getTask() {
		for (;;) {
			try {
				int state = runState;
				if (state > SHUTDOWN)
					return null;
				Runnable r;
				if (state == SHUTDOWN) // Help drain queue
					r = workQueue.poll();
				else if (poolSize > corePoolSize || allowCoreThreadTimeOut)
					r = workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS);
				else
					r = workQueue.take();
				if (r != null)
					return r;
				if (workerCanExit()) {
					if (runState >= SHUTDOWN) // Wake up others
						interruptIdleWorkers();
					return null;
				}
				// Else retry
			} catch (InterruptedException ie) {
				// On interruption, re-check runState
			}
		}
	}

	/**
	 * Check whether a worker thread that fails to get a task can exit. We allow
	 * a worker thread to die if the pool is stopping, or the queue is empty, or
	 * there is at least one thread to handle possibly non-empty queue, even if
	 * core timeouts are allowed.
	 */
	private boolean workerCanExit() {
		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		boolean canExit;
		try {
			canExit = runState >= STOP || workQueue.isEmpty()
					|| (allowCoreThreadTimeOut && poolSize > Math.max(1, corePoolSize));
		} finally {
			mainLock.unlock();
		}
		return canExit;
	}

	/**
	 * Wakes up all threads that might be waiting for tasks so they can check
	 * for termination. Note: this method is also called by
	 * ScheduledThreadPoolExecutor.
	 */
	void interruptIdleWorkers() {
		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		try {
			for (Worker w : workers)
				w.interruptIfIdle();
		} finally {
			mainLock.unlock();
		}
	}

	/**
	 * Performs bookkeeping for an exiting worker thread.
	 * @param w the worker
	 */
	void workerDone(Worker w) {
		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		try {
			completedTaskCount += w.completedTasks;
			workers.remove(w);
			if (--poolSize == 0)
				tryTerminate();
		} finally {
			mainLock.unlock();
		}
	}



	/**
	 * Transitions to TERMINATED state if either (SHUTDOWN and pool and queue
	 * empty) or (STOP and pool empty), otherwise unless stopped, ensuring that
	 * there is at least one live thread to handle queued tasks. This method is
	 * called from the three places in which termination can occur: in
	 * workerDone on exit of the last thread after pool has been shut down, or
	 * directly within calls to shutdown or shutdownNow, if there are no live
	 * threads.
	 */
	private void tryTerminate() {
		if (poolSize == 0) {
			int state = runState;
			if (state < STOP && !workQueue.isEmpty()) {
				state = RUNNING; // disable termination check below
				Thread t = addThread(null);
				if (t != null)
					t.start();
			}
			if (state == STOP || state == SHUTDOWN) {
				runState = TERMINATED;
				termination.signalAll();
				terminated();
			}
		}
	}

	/**
	 * Initiates an orderly shutdown in which previously submitted tasks are
	 * executed, but no new tasks will be accepted. Invocation has no additional
	 * effect if already shut down.
	 * @throws SecurityException if a security manager exists and shutting down
	 * this ExecutorService may manipulate threads that the caller is not
	 * permitted to modify because it does not hold
	 * {@link java.lang.RuntimePermission}<tt>("modifyThread")</tt>, or the
	 * security manager's <tt>checkAccess</tt> method denies access.
	 */
	public void shutdown() {
		/* Conceptually, shutdown is just a matter of changing the runState to
		 * SHUTDOWN, and then interrupting any worker threads that might be
		 * blocked in getTask() to wake them up so they can exit. Then, if there
		 * happen not to be any threads or tasks, we can directly terminate pool
		 * via tryTerminate. Else, the last worker to leave the building turns
		 * off the lights (in workerDone). But this is made more delicate
		 * because we must cooperate with the security manager (if present),
		 * which may implement policies that make more sense for operations on
		 * Threads than they do for ThreadPools. This requires 3 steps: 1.
		 * Making sure caller has permission to shut down threads in general
		 * (see shutdownPerm). 2. If (1) passes, making sure the caller is
		 * allowed to modify each of our threads. This might not be true even if
		 * first check passed, if the SecurityManager treats some threads
		 * specially. If this check passes, then we can try to set runState. 3.
		 * If both (1) and (2) pass, dealing with inconsistent security managers
		 * that allow checkAccess but then throw a SecurityException when
		 * interrupt() is invoked. In this third case, because we have already
		 * set runState, we can only try to back out from the shutdown as
		 * cleanly as possible. Some workers may have been killed but we remain
		 * in non-shutdown state (which may entail tryTerminate from workerDone
		 * starting a new worker to maintain liveness.) */

		SecurityManager security = System.getSecurityManager();
		if (security != null)
			security.checkPermission(shutdownPerm);

		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		try {
			if (security != null) { // Check if caller can modify our threads
				for (Worker w : workers)
					security.checkAccess(w.thread);
			}

			int state = runState;
			if (state < SHUTDOWN)
				runState = SHUTDOWN;

			try {
				for (Worker w : workers) {
					w.interruptIfIdle();
				}
			} catch (SecurityException se) { // Try to back out
				runState = state;
				// tryTerminate() here would be a no-op
				throw se;
			}

			tryTerminate(); // Terminate now if pool and queue empty
		} finally {
			mainLock.unlock();
		}
	}

	/**
	 * Attempts to stop all actively executing tasks, halts the processing of
	 * waiting tasks, and returns a list of the tasks that were awaiting
	 * execution. These tasks are drained (removed) from the task queue upon
	 * return from this method.
	 * <p>
	 * There are no guarantees beyond best-effort attempts to stop processing
	 * actively executing tasks. This implementation cancels tasks via
	 * {@link Thread#interrupt}, so any task that fails to respond to interrupts
	 * may never terminate.
	 * @return list of tasks that never commenced execution
	 * @throws SecurityException if a security manager exists and shutting down
	 * this ExecutorService may manipulate threads that the caller is not
	 * permitted to modify because it does not hold
	 * {@link java.lang.RuntimePermission}<tt>("modifyThread")</tt>, or the
	 * security manager's <tt>checkAccess</tt> method denies access.
	 */
	public List<Runnable> shutdownNow() {
		/* shutdownNow differs from shutdown only in that 1. runState is set to
		 * STOP, 2. all worker threads are interrupted, not just the idle ones,
		 * and 3. the queue is drained and returned. */
		SecurityManager security = System.getSecurityManager();
		if (security != null)
			security.checkPermission(shutdownPerm);

		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		try {
			if (security != null) { // Check if caller can modify our threads
				for (Worker w : workers)
					security.checkAccess(w.thread);
			}

			int state = runState;
			if (state < STOP)
				runState = STOP;

			try {
				for (Worker w : workers) {
					w.interruptNow();
				}
			} catch (SecurityException se) { // Try to back out
				runState = state;
				// tryTerminate() here would be a no-op
				throw se;
			}

			List<Runnable> tasks = drainQueue();
			tryTerminate(); // Terminate now if pool and queue empty
			return tasks;
		} finally {
			mainLock.unlock();
		}
	}

	/**
	 * Drains the task queue into a new list. Used by shutdownNow. Call only
	 * while holding main lock.
	 */
	private List<Runnable> drainQueue() {
		List<Runnable> taskList = new ArrayList<Runnable>();
		workQueue.drainTo(taskList);
		/* If the queue is a DelayQueue or any other kind of queue for which
		 * poll or drainTo may fail to remove some elements, we need to manually
		 * traverse and remove remaining tasks. To guarantee atomicity wrt other
		 * threads using this queue, we need to create a new iterator for each
		 * element removed. */
		while (!workQueue.isEmpty()) {
			Iterator<Runnable> it = workQueue.iterator();
			try {
				if (it.hasNext()) {
					Runnable r = it.next();
					if (workQueue.remove(r))
						taskList.add(r);
				}
			} catch (ConcurrentModificationException ignore) {
			}
		}
		return taskList;
	}

	public boolean isShutdown() {
		return runState != RUNNING;
	}

	/**
	 * Returns true if shutdownNow has been invoked but this executor has not
	 * completely terminated.
	 */
	boolean isStopped() {
		return runState == STOP;
	}

	/**
	 * Returns true if this executor is in the process of terminating after
	 * <tt>shutdown</tt> or <tt>shutdownNow</tt> but has not completely
	 * terminated. This method may be useful for debugging. A return of
	 * <tt>true</tt> reported a sufficient period after shutdown may indicate
	 * that submitted tasks have ignored or suppressed interruption, causing
	 * this executor not to properly terminate.
	 * @return true if terminating but not yet terminated
	 */
	public boolean isTerminating() {
		int state = runState;
		return state == SHUTDOWN || state == STOP;
	}

	public boolean isTerminated() {
		return runState == TERMINATED;
	}

	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		long nanos = unit.toNanos(timeout);
		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		try {
			for (;;) {
				if (runState == TERMINATED)
					return true;
				if (nanos <= 0)
					return false;
				nanos = termination.awaitNanos(nanos);
			}
		} finally {
			mainLock.unlock();
		}
	}

	/**
	 * Invokes <tt>shutdown</tt> when this executor is no longer referenced.
	 */
	protected void finalize() {
		shutdown();
	}



	/**
	 * Sets the thread factory used to create new threads.
	 * @param threadFactory the new thread factory
	 * @throws NullPointerException if threadFactory is null
	 * @see #getThreadFactory
	 */
	public void setThreadFactory(ThreadFactory threadFactory) {
		if (threadFactory == null)
			throw new NullPointerException();
		this.threadFactory = threadFactory;
	}

	/**
	 * Returns the thread factory used to create new threads.
	 * @return the current thread factory
	 * @see #setThreadFactory
	 */
	public ThreadFactory getThreadFactory() {
		return threadFactory;
	}

	/**
	 * Sets a new handler for unexecutable tasks.
	 * @param handler the new handler
	 * @throws NullPointerException if handler is null
	 * @see #getRejectedExecutionHandler
	 */
	public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
		if (handler == null)
			throw new NullPointerException();
		this.handler = handler;
	}

	/**
	 * Returns the current handler for unexecutable tasks.
	 * @return the current handler
	 * @see #setRejectedExecutionHandler
	 */
	public RejectedExecutionHandler getRejectedExecutionHandler() {
		return handler;
	}

	/**
	 * Sets the core number of threads. This overrides any value set in the
	 * constructor. If the new value is smaller than the current value, excess
	 * existing threads will be terminated when they next become idle. If
	 * larger, new threads will, if needed, be started to execute any queued
	 * tasks.
	 * @param corePoolSize the new core size
	 * @throws IllegalArgumentException if <tt>corePoolSize</tt> less than zero
	 * @see #getCorePoolSize
	 */
	public void setCorePoolSize(int corePoolSize) {
		if (corePoolSize < 0)
			throw new IllegalArgumentException();
		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		try {
			int extra = this.corePoolSize - corePoolSize;
			this.corePoolSize = corePoolSize;
			if (extra < 0) {
				int n = workQueue.size(); // don't add more threads than tasks
				while (extra++ < 0 && n-- > 0 && poolSize < corePoolSize) {
					Thread t = addThread(null);
					if (t != null)
						t.start();
					else
						break;
				}
			} else if (extra > 0 && poolSize > corePoolSize) {
				try {
					Iterator<Worker> it = workers.iterator();
					while (it.hasNext() && extra-- > 0 && poolSize > corePoolSize
							&& workQueue.remainingCapacity() == 0)
						it.next().interruptIfIdle();
				} catch (SecurityException ignore) {
					// Not an error; it is OK if the threads stay live
				}
			}
		} finally {
			mainLock.unlock();
		}
	}

	/**
	 * Returns the core number of threads.
	 * @return the core number of threads
	 * @see #setCorePoolSize
	 */
	public int getCorePoolSize() {
		return corePoolSize;
	}

	/**
	 * Starts a core thread, causing it to idly wait for work. This overrides
	 * the default policy of starting core threads only when new tasks are
	 * executed. This method will return <tt>false</tt> if all core threads have
	 * already been started.
	 * @return true if a thread was started
	 */
	public boolean prestartCoreThread() {
		return addIfUnderCorePoolSize(null);
	}

	/**
	 * Starts all core threads, causing them to idly wait for work. This
	 * overrides the default policy of starting core threads only when new tasks
	 * are executed.
	 * @return the number of threads started
	 */
	public int prestartAllCoreThreads() {
		int n = 0;
		while (addIfUnderCorePoolSize(null))
			++n;
		return n;
	}

	/**
	 * Returns true if this pool allows core threads to time out and terminate
	 * if no tasks arrive within the keepAlive time, being replaced if needed
	 * when new tasks arrive. When true, the same keep-alive policy applying to
	 * non-core threads applies also to core threads. When false (the default),
	 * core threads are never terminated due to lack of incoming tasks.
	 * @return <tt>true</tt> if core threads are allowed to time out, else
	 * <tt>false</tt>
	 * @since 1.6
	 */
	public boolean allowsCoreThreadTimeOut() {
		return allowCoreThreadTimeOut;
	}

	/**
	 * Sets the policy governing whether core threads may time out and terminate
	 * if no tasks arrive within the keep-alive time, being replaced if needed
	 * when new tasks arrive. When false, core threads are never terminated due
	 * to lack of incoming tasks. When true, the same keep-alive policy applying
	 * to non-core threads applies also to core threads. To avoid continual
	 * thread replacement, the keep-alive time must be greater than zero when
	 * setting <tt>true</tt>. This method should in general be called before the
	 * pool is actively used.
	 * @param value <tt>true</tt> if should time out, else <tt>false</tt>
	 * @throws IllegalArgumentException if value is <tt>true</tt> and the
	 * current keep-alive time is not greater than zero.
	 * @since 1.6
	 */
	public void allowCoreThreadTimeOut(boolean value) {
		if (value && keepAliveTime <= 0)
			throw new IllegalArgumentException("Core threads must have nonzero keep alive times");

		allowCoreThreadTimeOut = value;
	}

	/**
	 * Sets the maximum allowed number of threads. This overrides any value set
	 * in the constructor. If the new value is smaller than the current value,
	 * excess existing threads will be terminated when they next become idle.
	 * @param maximumPoolSize the new maximum
	 * @throws IllegalArgumentException if the new maximum is less than or equal
	 * to zero, or less than the {@linkplain #getCorePoolSize core pool size}
	 * @see #getMaximumPoolSize
	 */
	public void setMaximumPoolSize(int maximumPoolSize) {
		if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize)
			throw new IllegalArgumentException();
		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		try {
			int extra = this.maximumPoolSize - maximumPoolSize;
			this.maximumPoolSize = maximumPoolSize;
			if (extra > 0 && poolSize > maximumPoolSize) {
				try {
					Iterator<Worker> it = workers.iterator();
					while (it.hasNext() && extra > 0 && poolSize > maximumPoolSize) {
						it.next().interruptIfIdle();
						--extra;
					}
				} catch (SecurityException ignore) {
					// Not an error; it is OK if the threads stay live
				}
			}
		} finally {
			mainLock.unlock();
		}
	}

	/**
	 * Returns the maximum allowed number of threads.
	 * @return the maximum allowed number of threads
	 * @see #setMaximumPoolSize
	 */
	public int getMaximumPoolSize() {
		return maximumPoolSize;
	}

	/**
	 * Sets the time limit for which threads may remain idle before being
	 * terminated. If there are more than the core number of threads currently
	 * in the pool, after waiting this amount of time without processing a task,
	 * excess threads will be terminated. This overrides any value set in the
	 * constructor.
	 * @param time the time to wait. A time value of zero will cause excess
	 * threads to terminate immediately after executing tasks.
	 * @param unit the time unit of the time argument
	 * @throws IllegalArgumentException if time less than zero or if time is
	 * zero and allowsCoreThreadTimeOut
	 * @see #getKeepAliveTime
	 */
	public void setKeepAliveTime(long time, TimeUnit unit) {
		if (time < 0)
			throw new IllegalArgumentException();
		if (time == 0 && allowsCoreThreadTimeOut())
			throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
		this.keepAliveTime = unit.toNanos(time);
	}

	/**
	 * Returns the thread keep-alive time, which is the amount of time that
	 * threads in excess of the core pool size may remain idle before being
	 * terminated.
	 * @param unit the desired time unit of the result
	 * @return the time limit
	 * @see #setKeepAliveTime
	 */
	public long getKeepAliveTime(TimeUnit unit) {
		return unit.convert(keepAliveTime, TimeUnit.NANOSECONDS);
	}



	/**
	 * Returns the task queue used by this executor. Access to the task queue is
	 * intended primarily for debugging and monitoring. This queue may be in
	 * active use. Retrieving the task queue does not prevent queued tasks from
	 * executing.
	 * @return the task queue
	 */
	public BlockingQueue<Runnable> getQueue() {
		return workQueue;
	}

	/**
	 * Removes this task from the executor's internal queue if it is present,
	 * thus causing it not to be run if it has not already started.
	 * <p>
	 * This method may be useful as one part of a cancellation scheme. It may
	 * fail to remove tasks that have been converted into other forms before
	 * being placed on the internal queue. For example, a task entered using
	 * <tt>submit</tt> might be converted into a form that maintains
	 * <tt>Future</tt> status. However, in such cases, method
	 * {@link ThreadPoolExecutor#purge} may be used to remove those Futures that
	 * have been cancelled.
	 * @param task the task to remove
	 * @return true if the task was removed
	 */
	public boolean remove(Runnable task) {
		return getQueue().remove(task);
	}

	/**
	 * Tries to remove from the work queue all {@link Future} tasks that have
	 * been cancelled. This method can be useful as a storage reclamation
	 * operation, that has no other impact on functionality. Cancelled tasks are
	 * never executed, but may accumulate in work queues until worker threads
	 * can actively remove them. Invoking this method instead tries to remove
	 * them now. However, this method may fail to remove tasks in the presence
	 * of interference by other threads.
	 */
	public void purge() {
		// Fail if we encounter interference during traversal
		try {
			Iterator<Runnable> it = getQueue().iterator();
			while (it.hasNext()) {
				Runnable r = it.next();
				if (r instanceof Future<?>) {
					Future<?> c = (Future<?>) r;
					if (c.isCancelled())
						it.remove();
				}
			}
		} catch (ConcurrentModificationException ex) {
			return;
		}
	}



	/**
	 * Returns the current number of threads in the pool.
	 * @return the number of threads
	 */
	public int getPoolSize() {
		return poolSize;
	}

	/**
	 * Returns the approximate number of threads that are actively executing
	 * tasks.
	 * @return the number of threads
	 */
	public int getActiveCount() {
		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		try {
			int n = 0;
			for (Worker w : workers) {
				if (w.isActive())
					++n;
			}
			return n;
		} finally {
			mainLock.unlock();
		}
	}

	/**
	 * Returns the largest number of threads that have ever simultaneously been
	 * in the pool.
	 * @return the number of threads
	 */
	public int getLargestPoolSize() {
		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		try {
			return largestPoolSize;
		} finally {
			mainLock.unlock();
		}
	}

	/**
	 * Returns the approximate total number of tasks that have ever been
	 * scheduled for execution. Because the states of tasks and threads may
	 * change dynamically during computation, the returned value is only an
	 * approximation.
	 * @return the number of tasks
	 */
	public long getTaskCount() {
		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		try {
			long n = completedTaskCount;
			for (Worker w : workers) {
				n += w.completedTasks;
				if (w.isActive())
					++n;
			}
			return n + workQueue.size();
		} finally {
			mainLock.unlock();
		}
	}

	/**
	 * Returns the approximate total number of tasks that have completed
	 * execution. Because the states of tasks and threads may change dynamically
	 * during computation, the returned value is only an approximation, but one
	 * that does not ever decrease across successive calls.
	 * @return the number of tasks
	 */
	public long getCompletedTaskCount() {
		final ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		try {
			long n = completedTaskCount;
			for (Worker w : workers)
				n += w.completedTasks;
			return n;
		} finally {
			mainLock.unlock();
		}
	}



	/**
	 * Method invoked prior to executing the given Runnable in the given thread.
	 * This method is invoked by thread <tt>t</tt> that will execute task
	 * <tt>r</tt>, and may be used to re-initialize ThreadLocals, or to perform
	 * logging.
	 * <p>
	 * This implementation does nothing, but may be customized in subclasses.
	 * Note: To properly nest multiple overridings, subclasses should generally
	 * invoke <tt>super.beforeExecute</tt> at the end of this method.
	 * @param t the thread that will run task r.
	 * @param r the task that will be executed.
	 */
	protected void beforeExecute(Thread t, Runnable r) {
	}

	/**
	 * Method invoked upon completion of execution of the given Runnable. This
	 * method is invoked by the thread that executed the task. If non-null, the
	 * Throwable is the uncaught <tt>RuntimeException</tt> or <tt>Error</tt>
	 * that caused execution to terminate abruptly.
	 * <p>
	 * <b>Note:</b> When actions are enclosed in tasks (such as
	 * {@link FutureTask}) either explicitly or via methods such as
	 * <tt>submit</tt>, these task objects catch and maintain computational
	 * exceptions, and so they do not cause abrupt termination, and the internal
	 * exceptions are <em>not</em> passed to this method.
	 * <p>
	 * This implementation does nothing, but may be customized in subclasses.
	 * Note: To properly nest multiple overridings, subclasses should generally
	 * invoke <tt>super.afterExecute</tt> at the beginning of this method.
	 * @param r the runnable that has completed.
	 * @param t the exception that caused termination, or null if execution
	 * completed normally.
	 */
	protected void afterExecute(Runnable r, Throwable t) {
	}

	/**
	 * Method invoked when the Executor has terminated. Default implementation
	 * does nothing. Note: To properly nest multiple overridings, subclasses
	 * should generally invoke <tt>super.terminated</tt> within this method.
	 */
	protected void terminated() {
	}



	/**
	 * A handler for rejected tasks that runs the rejected task directly in the
	 * calling thread of the <tt>execute</tt> method, unless the executor has
	 * been shut down, in which case the task is discarded.
	 */
	public static class CallerRunsPolicy implements RejectedExecutionHandler {
		/**
		 * Creates a <tt>CallerRunsPolicy</tt>.
		 */
		public CallerRunsPolicy() {
		}

		/**
		 * Executes task r in the caller's thread, unless the executor has been
		 * shut down, in which case the task is discarded.
		 * @param r the runnable task requested to be executed
		 * @param e the executor attempting to execute this task
		 */
		public void rejectedExecution(Runnable r, FixThreadPoolExecutor e) {
			if (!e.isShutdown()) {
				r.run();
			}
		}
	}

	/**
	 * A handler for rejected tasks that throws a
	 * <tt>RejectedExecutionException</tt>.
	 */
	public static class AbortPolicy implements RejectedExecutionHandler {
		/**
		 * Creates an <tt>AbortPolicy</tt>.
		 */
		public AbortPolicy() {
		}

		/**
		 * Always throws RejectedExecutionException.
		 * @param r the runnable task requested to be executed
		 * @param e the executor attempting to execute this task
		 * @throws RejectedExecutionException always.
		 */
		public void rejectedExecution(Runnable r, FixThreadPoolExecutor e) {
			throw new RejectedExecutionException();
		}
	}

	/**
	 * A handler for rejected tasks that silently discards the rejected task.
	 */
	public static class DiscardPolicy implements RejectedExecutionHandler {
		/**
		 * Creates a <tt>DiscardPolicy</tt>.
		 */
		public DiscardPolicy() {
		}

		/**
		 * Does nothing, which has the effect of discarding task r.
		 * @param r the runnable task requested to be executed
		 * @param e the executor attempting to execute this task
		 */
		public void rejectedExecution(Runnable r, FixThreadPoolExecutor e) {
		}
	}

	/**
	 * A handler for rejected tasks that discards the oldest unhandled request
	 * and then retries <tt>execute</tt>, unless the executor is shut down, in
	 * which case the task is discarded.
	 */
	public static class DiscardOldestPolicy implements RejectedExecutionHandler {
		/**
		 * Creates a <tt>DiscardOldestPolicy</tt> for the given executor.
		 */
		public DiscardOldestPolicy() {
		}

		/**
		 * Obtains and ignores the next task that the executor would otherwise
		 * execute, if one is immediately available, and then retries execution
		 * of task r, unless the executor is shut down, in which case task r is
		 * instead discarded.
		 * @param r the runnable task requested to be executed
		 * @param e the executor attempting to execute this task
		 */
		public void rejectedExecution(Runnable r, FixThreadPoolExecutor e) {
			if (!e.isShutdown()) {
				e.getQueue().poll();
				e.execute(r);
			}
		}
	}
}
