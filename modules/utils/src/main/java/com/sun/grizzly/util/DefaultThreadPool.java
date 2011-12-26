/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */

package com.sun.grizzly.util;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * {@link ExecutorService} implementation, which function the similar way as
 * former Grizzly 1.x Pipeline based thread pools.
 * Unlike {@link DefaultThreadPool}, this thread pool, if there are no free
 * worker threads available, prefers to create new worker thread, up-to maximum,
 * to process new task. And only if max threads count is reached - this
 * thread pool will start to add tasks to queue. Where {@link DefaultThreadPool}
 * first tries to add new task to queue, and only if task queue reaches its
 * maximum - creates new worker thread.
 *
 * corethreads are prestarted.<br>
 * maxPoolSize is runtime configurable.<br><br>
 *
 * Designed around the use of lockfree queue.<br>
 * less performant then {@link FixedThreadPool} due to keeping track
 * of queue size to know when to spawn a worker or not, creating a chokepoint between producers and consumers
 * in the form of looping around compareAndSet inside {@link AtomicInteger} to update the queue size for each put and get of a task .<br>
 * for short lived tasks at saturation throughput this overhead can be substantial on some platforms.<br>
 * by default: {@link WorkerThreadImpl} is used,
 * {@link LinkedTransferQueue} is used as workQueue , this means that its FIFO per producer.<br>
 *
 * @author gustav trede
 */
public class DefaultThreadPool extends FixedThreadPool
        implements Thread.UncaughtExceptionHandler{

    private final AtomicInteger queueSize = new AtomicInteger();

    protected final AtomicInteger workerThreadCounter = new AtomicInteger();

    /**
     *
     */
    public DefaultThreadPool() {
        this("Grizzly", DEFAULT_MIN_THREAD_COUNT, DEFAULT_MAX_THREAD_COUNT,
                DEFAULT_IDLE_THREAD_KEEPALIVE_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    /**
     *
     * @param workerprefixname
     * @param corePoolsize
     * @param maxPoolSize
     * @param keepAliveTime
     * @param timeUnit {@link TimeUnit}
     */
    public DefaultThreadPool(final String name, int corePoolsize,
            int maxPoolSize, long keepAliveTime, TimeUnit timeUnit){
        this(name, corePoolsize, maxPoolSize, keepAliveTime, timeUnit, null);
    }

    /**
     *
     * @param corePoolsize
     * @param maxPoolSize
     * @param keepAliveTime
     * @param timeUnit  {@link TimeUnit}
     * @param threadFactory {@link ThreadFactory}
     */
    public DefaultThreadPool(final String name, int corePoolsize,
            int maxPoolSize, long keepAliveTime, TimeUnit timeUnit,
            ThreadFactory threadFactory) {
            this(name, corePoolsize, maxPoolSize, keepAliveTime, timeUnit,
                    threadFactory, new LinkedTransferQueue<Runnable>());
    }

    /**
     *
     * @param corePoolsize
     * @param maxPoolSize
     * @param keepAliveTime
     * @param timeUnit {@link TimeUnit}
     * @param threadFactory {@link ThreadFactory}
     * @param workQueue {@link BlockingQueue}
     */
    public DefaultThreadPool(final String name, int corePoolsize,int maxPoolSize,
            long keepAliveTime, TimeUnit timeUnit, ThreadFactory threadFactory,
            BlockingQueue<Runnable> workQueue) {

        super(workQueue, threadFactory);

        if (keepAliveTime< 0 )
            throw new IllegalArgumentException("keepAliveTime < 0");
        if (timeUnit == null)
            throw new IllegalArgumentException("timeUnit == null");

        setPoolSizes(corePoolsize, maxPoolSize);

        this.keepAliveTime = TimeUnit.MILLISECONDS.convert(keepAliveTime, timeUnit);
        this.name = name;

        if (this.threadFactory == null) {
            this.threadFactory = new DefaultWorkerThreadFactory();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(Runnable task) {
        if (task == null){
            throw new IllegalArgumentException("Runnable task is null");
        }

        int aliveWorkers;
        while((aliveWorkers=aliveworkerCount.get())<maxPoolSize &&
                (aliveWorkers < corePoolSize ||
                queueSize.get()>0 || !hasIdleWorkersApproximately()) && running){
            if (aliveworkerCount.compareAndSet(aliveWorkers, aliveWorkers+1)){
                onTaskQueued(task);
                startWorker(new DefaultThreadWorker(task, false));
                return;
            }
        }
        if (running) {
            if (workQueue.offer(task)) {
                if (aliveWorkers >= maxPoolSize) {
                    onMaxNumberOfThreadsReached();
                }

                onTaskQueued(task);
            } else {
                onTaskQueueOverflow();
                throw new RejectedExecutionException("The queue is full");
            }
        }
    }

    private boolean hasIdleWorkersApproximately() {
        if( aliveworkerCount.get() <= approximateRunningWorkerCount.get() )
            return false;
        else
            return true;
    }

    public void start() {
        int aliveCount;
        while((aliveCount = aliveworkerCount.get()) < corePoolSize) {
            if (aliveworkerCount.compareAndSet(aliveCount, aliveCount + 1)) {
                startWorker(new DefaultThreadWorker(null,true));
            }
        }
    }

    public void stop() {
        shutdownNow();
    }

    @Override
    protected void onTaskQueued(Runnable task) {
        super.onTaskQueued(task);
        queueSize.incrementAndGet();
    }

    @Override
    protected void onTaskDequeued(Runnable task) {
        queueSize.decrementAndGet();
        super.onTaskDequeued(task);
    }

    protected class DefaultThreadWorker extends BasicWorker {
        private final boolean core;
        private Runnable firstTask;

        public DefaultThreadWorker(Runnable firstTask, boolean core) {
            this.core = core;
            this.firstTask = firstTask;
        }

        @Override
        protected Runnable getTask() throws InterruptedException {
            Runnable r;
            if (firstTask != null) {
                r = firstTask;
                firstTask = null;
            } else {
                // if maxpoolsize becomes lower during runtime we kill of the
                // difference, possible abit more since we are not looping around compareAndSet
                if (!core && aliveworkerCount.get() > maxPoolSize) {
                    return null;
                }
                r = (core ? workQueue.take() : workQueue.poll(keepAliveTime, TimeUnit.MILLISECONDS));
            }

            return r;
        }
    }

    @Override
    public int getQueueSize() {
        return queueSize.get();
    }

    protected void setPoolSizes(int corePoolSize, int maxPoolSize) {
        synchronized(statelock){
            validateNewPoolSize(corePoolSize, maxPoolSize);

            this.corePoolSize = corePoolSize;
            this.maxPoolSize = maxPoolSize;
        }
    }

    @Override
    public void setCorePoolSize(int corePoolSize) {
        synchronized(statelock){
            validateNewPoolSize(corePoolSize, maxPoolSize);
            this.corePoolSize = corePoolSize;
        }
    }

    @Override
    public int getCorePoolSize() {
        return corePoolSize;
    }

    /**
     *
     * @param maxPoolSize
     */
    @Override
    public void setMaximumPoolSize(int maxPoolSize) {
        synchronized(statelock){
            validateNewPoolSize(corePoolSize, maxPoolSize);
            this.maxPoolSize = maxPoolSize;
        }
    }


    @Override
    public int getMaximumPoolSize() {
        return maxPoolSize;
    }

    @Override
    protected String nextThreadId() {
        return Integer.toString(workerThreadCounter.getAndIncrement());
    }


    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(512);
        builder.append("DefaultThreadPool[");
        injectToStringAttributes(builder);
        builder.append(']');
        return builder.toString();
    }

    protected void injectToStringAttributes(StringBuilder sb) {
        sb.append("name=").append(name);
        sb.append(", min-threads=").append(getCorePoolSize());
        sb.append(", max-threads=").append(getMaximumPoolSize());
        sb.append(", max-queue-size=").append(getMaxQueuedTasksCount());
        sb.append(", is-shutdown=").append(isShutdown());
    }

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        super.beforeExecute(t, r);
        ((WorkerThreadImpl) t).createByteBuffer(false);
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        ((WorkerThreadImpl) Thread.currentThread()).reset();
        super.afterExecute(r, t);
    }

    private class DefaultWorkerThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            Thread thread = new WorkerThreadImpl(DefaultThreadPool.this,
                    name + "-WorkerThread(" +
                    nextThreadId() + ")", r,
                    initialByteBufferSize);
            thread.setUncaughtExceptionHandler(DefaultThreadPool.this);
            thread.setPriority(priority);
            return thread;
        }
    }
}