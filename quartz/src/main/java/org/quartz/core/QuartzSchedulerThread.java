
/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */

package org.quartz.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import org.quartz.JobPersistenceException;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.Trigger.CompletedExecutionInstruction;
import org.quartz.spi.JobStore;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.TriggerFiredBundle;
import org.quartz.spi.TriggerFiredResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * The thread responsible for performing the work of firing <code>{@link Trigger}</code>
 * s that are registered with the <code>{@link QuartzScheduler}</code>.
 * </p>
 *
 * @see QuartzScheduler
 * @see org.quartz.Job
 * @see Trigger
 *
 * @author James House
 */
public class QuartzSchedulerThread extends Thread {
    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     *
     * Data members.
     *
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */
    private QuartzScheduler qs;

    private QuartzSchedulerResources qsRsrcs;

    private final Object sigLock = new Object();

    private boolean signaled;
    private long signaledNextFireTime;

    private boolean paused;

    private AtomicBoolean halted;

    private Random random = new Random(System.currentTimeMillis());

    // When the scheduler finds there is no current trigger to fire, how long
    // it should wait until checking again...
    private static long DEFAULT_IDLE_WAIT_TIME = 30L * 1000L;

    private long idleWaitTime = DEFAULT_IDLE_WAIT_TIME;

    private int idleWaitVariablness = 7 * 1000;

    private final Logger log = LoggerFactory.getLogger(getClass());

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     *
     * Constructors.
     *
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * Construct a new <code>QuartzSchedulerThread</code> for the given
     * <code>QuartzScheduler</code> as a non-daemon <code>Thread</code>
     * with normal priority.
     * </p>
     */
    QuartzSchedulerThread(QuartzScheduler qs, QuartzSchedulerResources qsRsrcs) {
        this(qs, qsRsrcs, qsRsrcs.getMakeSchedulerThreadDaemon(), Thread.NORM_PRIORITY);
    }

    /**
     * <p>
     * Construct a new <code>QuartzSchedulerThread</code> for the given
     * <code>QuartzScheduler</code> as a <code>Thread</code> with the given
     * attributes.
     * </p>
     */
    QuartzSchedulerThread(QuartzScheduler qs, QuartzSchedulerResources qsRsrcs, boolean setDaemon, int threadPrio) {
        super(qs.getSchedulerThreadGroup(), qsRsrcs.getThreadName());
        this.qs = qs;
        this.qsRsrcs = qsRsrcs;
        this.setDaemon(setDaemon);
        if(qsRsrcs.isThreadsInheritInitializersClassLoadContext()) {
            log.info("QuartzSchedulerThread Inheriting ContextClassLoader of thread: " + Thread.currentThread().getName());
            this.setContextClassLoader(Thread.currentThread().getContextClassLoader());
        }

        this.setPriority(threadPrio);

        // start the underlying thread, but put this object into the 'paused'
        // state
        // so processing doesn't start yet...
        paused = true;
        halted = new AtomicBoolean(false);
    }

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     *
     * Interface.
     *
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    void setIdleWaitTime(long waitTime) {
        idleWaitTime = waitTime;
        idleWaitVariablness = (int) (waitTime * 0.2);
    }

    private long getRandomizedIdleWaitTime() {
        return idleWaitTime - random.nextInt(idleWaitVariablness);
    }

    /**
     * <p>
     * Signals the main processing loop to pause at the next possible point.
     * </p>
     */
    void togglePause(boolean pause) {
        // 作用：暂停线程
        synchronized (sigLock) {
            paused = pause;
            if (paused) {
                signalSchedulingChange(0);
            } else {
                // 信号锁立马唤醒线程
                sigLock.notifyAll();
            }
        }
    }

    /**
     * <p>
     * Signals the main processing loop to pause at the next possible point.
     * </p>
     */
    void halt(boolean wait) {
        // 获取对象锁
        synchronized (sigLock) {
            // 停止状态为true
            halted.set(true);
            // 唤醒线程本身
            if (paused) {
                sigLock.notifyAll();
            } else {
                signalSchedulingChange(0);
            }
        }
        // 立马中断线程
        this.interrupt();

        if (wait) {
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        // TODO https://zhuanlan.zhihu.com/p/258581678
                        join();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    boolean isPaused() {
        return paused;
    }

    /**
     * <p>
     * Signals the main processing loop that a change in scheduling has been
     * made - in order to interrupt any sleeping that may be occuring while
     * waiting for the fire time to arrive.
     * </p>
     *
     * @param candidateNewNextFireTime the time (in millis) when the newly scheduled trigger
     * will fire.  If this method is being called do to some other even (rather
     * than scheduling a trigger), the caller should pass zero (0).
     */
    public void signalSchedulingChange(long candidateNewNextFireTime) {
        synchronized(sigLock) {
            // 发过信号了
            signaled = true;
            // 暂停所有线程,下次调度的时间
            // TODO 下次调度==0怎么处理？
            signaledNextFireTime = candidateNewNextFireTime;
            // 唤醒所有的调度线程,也就是本身
            sigLock.notifyAll();
        }
    }

    public void clearSignaledSchedulingChange() {
        // 重置下信号状态
        synchronized(sigLock) {
            signaled = false;
            signaledNextFireTime = 0;
        }
    }

    public boolean isScheduleChanged() {
        synchronized(sigLock) {
            return signaled;
        }
    }

    public long getSignaledNextFireTime() {
        synchronized(sigLock) {
            return signaledNextFireTime;
        }
    }

    /**
     * <p>
     * The main processing loop of the <code>QuartzSchedulerThread</code>.
     * </p>
     */

    @Override
    public void run() {
        // 调度失败的个数
        int acquiresFailed = 0;

        // 没有可以调度的trigger则是大约30s检测一次
        // 未挂机
        while (!halted.get()) {
            try {
                // check if we're supposed to pause...
                synchronized (sigLock) {
                    // 是否处于暂停状态，暂停就需要等待1s
                    while (paused && !halted.get()) {
                        try {
                            // wait until togglePause(false) is called...
                            sigLock.wait(1000L);
                        } catch (InterruptedException ignore) {
                        }

                        // reset failure counter when paused, so that we don't
                        // wait again after unpausing
                        acquiresFailed = 0;
                    }
                    // 挂机了线程退出
                    if (halted.get()) {
                        break;
                    }
                }

                // wait a bit, if reading from job store is consistently
                // failing (e.g. DB is down or restarting)..
                if (acquiresFailed > 1) {
                    try {
                        // 出现失败情况就进行休眠重试
                        long delay = computeDelayForRepeatedErrors(qsRsrcs.getJobStore(), acquiresFailed);
                        Thread.sleep(delay);
                    } catch (Exception ignore) {
                    }
                }
                // 获取可用的线程数
                int availThreadCount = qsRsrcs.getThreadPool().blockForAvailableThreads();
                // 挂机
                synchronized (sigLock) {
                    if (halted.get()) {
                        break;
                    }
                }
                if(availThreadCount > 0) { // will always be true, due to semantics of blockForAvailableThreads...

                    List<OperableTrigger> triggers;

                    long now = System.currentTimeMillis();
                    // 重置信号 false,没有可以调度的
                    clearSignaledSchedulingChange();
                    try {
                        // 获取availThreadCount,MaxBatchSize，BatchTimeWindow最小允许被调度的触发器数量
                        triggers = qsRsrcs.getJobStore().acquireNextTriggers(
                                now + idleWaitTime, Math.min(availThreadCount, qsRsrcs.getMaxBatchSize()), qsRsrcs.getBatchTimeWindow());
                        acquiresFailed = 0;
                        if (log.isDebugEnabled())
                            log.debug("batch acquisition of " + (triggers == null ? 0 : triggers.size()) + " triggers");
                    } catch (JobPersistenceException jpe)
                    {

                        // 数据库访问异常也会进行重试
                        if (acquiresFailed == 0) {
                            qs.notifySchedulerListenersError(
                                "An error occurred while scanning for the next triggers to fire.",
                                jpe);
                        }
                        if (acquiresFailed < Integer.MAX_VALUE)
                            acquiresFailed++;
                        // 退出当前循环
                        continue;
                    }
                    catch (RuntimeException e)
                    {
                        // 运行时异常会不断重试？
                        if (acquiresFailed == 0) {
                            getLog().error("quartzSchedulerThreadLoop: RuntimeException "
                                    +e.getMessage(), e);
                        }
                        if (acquiresFailed < Integer.MAX_VALUE)
                            acquiresFailed++;
                        continue;
                    }

                    if (triggers != null && !triggers.isEmpty()) {

                        now = System.currentTimeMillis();
                        // 计算触发器调度时间>2毫秒
                        long triggerTime = triggers.get(0).getNextFireTime().getTime();
                        long timeUntilTrigger = triggerTime - now;
                        while(timeUntilTrigger > 2) {
                            synchronized (sigLock) {
                                if (halted.get()) {
                                    break;
                                }
                                // TODO 在合理的时间内提前新的候选人
                                if (!isCandidateNewTimeEarlierWithinReason(triggerTime, false)) {
                                    try {
                                        // we could have blocked a long while
                                        // on 'synchronize', so we must recompute
                                        // 我们可能已经阻塞了很长一段时间’同步’，所以我们必须重新计算
                                        now = System.currentTimeMillis();
                                        timeUntilTrigger = triggerTime - now;
                                        if(timeUntilTrigger >= 1)
                                            sigLock.wait(timeUntilTrigger);
                                    } catch (InterruptedException ignore) {
                                    }
                                }
                            }
                            synchronized (sigLock) {
                                if (halted.get()) {
                                    break;
                                }
                            }
                            //释放调度
                            if(releaseIfScheduleChangedSignificantly(triggers, triggerTime)) {
                                break;
                            }
                            now = System.currentTimeMillis();
                            timeUntilTrigger = triggerTime - now;
                        }

                        // 如果决定释放触发器，就会发生这种情况
                        // this happens if releaseIfScheduleChangedSignificantly decided to release triggers
                        if(triggers.isEmpty())
                            continue;

                        // set triggers to 'executing'
                        List<TriggerFiredResult> bndles = new ArrayList<TriggerFiredResult>();

                        boolean goAhead = true;
                        synchronized(sigLock) {
                            goAhead = !halted.get();
                        }
                        if(goAhead) {
                            try {
                                // 开始批量调度
                                List<TriggerFiredResult> res = qsRsrcs.getJobStore().triggersFired(triggers);
                                if(res != null)
                                    bndles = res;
                            } catch (SchedulerException se) {
                                // 批量调度异常
                                qs.notifySchedulerListenersError(
                                        "An error occurred while firing triggers '"
                                                + triggers + "'", se);
                                //QTZ-179 : a problem occurred interacting with the triggers from the db
                                //we release them and loop again
                                for (int i = 0; i < triggers.size(); i++) {
                                    qsRsrcs.getJobStore().releaseAcquiredTrigger(triggers.get(i));
                                }
                                continue;
                            }

                        }

                        for (int i = 0; i < bndles.size(); i++) {
                            TriggerFiredResult result =  bndles.get(i);
                            TriggerFiredBundle bndle =  result.getTriggerFiredBundle();
                            Exception exception = result.getException();

                            // 挑选出异常情况的并记录下来
                            if (exception instanceof RuntimeException) {
                                getLog().error("RuntimeException while firing trigger " + triggers.get(i), exception);
                                qsRsrcs.getJobStore().releaseAcquiredTrigger(triggers.get(i));
                                continue;
                            }

                            // it's possible to get 'null' if the triggers was paused,
                            // blocked, or other similar occurrences that prevent it being
                            // fired at this time...  or if the scheduler was shutdown (halted)
                            /**
                             * 如果触发器被暂停、阻塞或者其他类似的事件阻止它在这个时候被触发，或者如果调度程序被关闭(停止) ，那么可能会得到‘ null’
                             */
                            if (bndle == null) {
                                qsRsrcs.getJobStore().releaseAcquiredTrigger(triggers.get(i));
                                continue;
                            }

                            JobRunShell shell = null;
                            try {
                                // 初始化job执行 脚本
                                shell = qsRsrcs.getJobRunShellFactory().createJobRunShell(bndle);
                                shell.initialize(qs);
                            } catch (SchedulerException se) {
                                qsRsrcs.getJobStore().triggeredJobComplete(triggers.get(i), bndle.getJobDetail(), CompletedExecutionInstruction.SET_ALL_JOB_TRIGGERS_ERROR);
                                continue;
                            }

                            // 将脚本放到线程池里执行,运行失败了
                            if (qsRsrcs.getThreadPool().runInThread(shell) == false) {
                                // this case should never happen, as it is indicative of the
                                // scheduler being shutdown or a bug in the thread pool or
                                // a thread pool being used concurrently - which the docs
                                // say not to do...
                                getLog().error("ThreadPool.runInThread() return false!");
                                qsRsrcs.getJobStore().triggeredJobComplete(triggers.get(i), bndle.getJobDetail(), CompletedExecutionInstruction.SET_ALL_JOB_TRIGGERS_ERROR);
                            }

                        }

                        continue; // while (!halted)
                    }
                }
                else {
                    // 没有可用的工作线程则等待下一轮循环,忙循环
                    // if(availThreadCount > 0)
                    // should never happen, if threadPool.blockForAvailableThreads() follows contract
                    continue; // while (!halted)
                }

                long now = System.currentTimeMillis();
                // 设置一个随机等待时间  23-30s 不等
                long waitTime = now + getRandomizedIdleWaitTime();
                // 随机等待时间
                long timeUntilContinue = waitTime - now;
                synchronized(sigLock) {
                    try {
                      if(!halted.get()) {
                          /**
                           * 一个作业可能已经在同一时间内完成，我们可能因为没有等待通知()而错过了预定的更改信号，在等待太长时间之前检查一下，以防这个作业需要很快进行调度
                           */
                        // QTZ-336 A job might have been completed in the mean time and we might have
                        // missed the scheduled changed signal by not waiting for the notify() yet
                        // Check that before waiting for too long in case this very job needs to be
                        // scheduled very soon
                          // 如果没有等待通知()，我们错过预定的更改信号，在等待太长时间之前检查一下，以防这个作业需要很快进行调度
                          // 没有信号则进行等待 23-30s
                          // 处理完成之后没有变更或信号量没变化就要等待
                        if (!isScheduleChanged()) {
                          sigLock.wait(timeUntilContinue);
                        }
                      }
                    } catch (InterruptedException ignore) {
                    }
                }

            } catch(RuntimeException re) {
                getLog().error("Runtime error occurred in main trigger firing loop.", re);
            }
        } // while (!halted)

        // drop references to scheduler stuff to aid garbage collection...
        qs = null;
        qsRsrcs = null;
    }

    // 20毫秒
    private static final long MIN_DELAY = 20;
    // 10分钟
    private static final long MAX_DELAY = 600000;

    /**
     * 计算重试间隔
     */
    private static long computeDelayForRepeatedErrors(JobStore jobStore, int acquiresFailed) {
        long delay;
        try {
            // 延迟间隔,默认是同一个已经配置好的重试间隔，也可以自己实现重试策略
            delay = jobStore.getAcquireRetryDelay(acquiresFailed);
        } catch (Exception ignored) {
            // we're trying to be useful in case of error states, not cause
            // additional errors..
            delay = 100;
        }

        //重试间隔必须在 20ms - 10min之间
        // sanity check per getAcquireRetryDelay specification
        if (delay < MIN_DELAY)
            delay = MIN_DELAY;
        if (delay > MAX_DELAY)
            delay = MAX_DELAY;

        return delay;
    }

    private boolean releaseIfScheduleChangedSignificantly(
            List<OperableTrigger> triggers, long triggerTime) {
        // 已经发生提前调度,此时就不应该再次调度了
        if (isCandidateNewTimeEarlierWithinReason(triggerTime, true)) {
            // above call does a clearSignaledSchedulingChange()
            for (OperableTrigger trigger : triggers) {
                // 释放Acquired状态的触发器
                qsRsrcs.getJobStore().releaseAcquiredTrigger(trigger);
            }
            triggers.clear();
            return true;
        }
        return false;
    }

    private boolean isCandidateNewTimeEarlierWithinReason(long oldTime, boolean clearSignal) {

        // So here's the deal: We know due to being signaled that 'the schedule'
        // has changed.  We may know (if getSignaledNextFireTime() != 0) the
        // new earliest fire time.  We may not (in which case we will assume
        // that the new time is earlier than the trigger we have acquired).
        // In either case, we only want to abandon our acquired trigger and
        // go looking for a new one if "it's worth it".  It's only worth it if
        // the time cost incurred to abandon the trigger and acquire a new one
        // is less than the time until the currently acquired trigger will fire,
        // otherwise we're just "thrashing" the job store (e.g. database).
        //
        // So the question becomes when is it "worth it"?  This will depend on
        // the job store implementation (and of course the particular database
        // or whatever behind it).  Ideally we would depend on the job store
        // implementation to tell us the amount of time in which it "thinks"
        // it can abandon the acquired trigger and acquire a new one.  However
        // we have no current facility for having it tell us that, so we make
        // a somewhat educated but arbitrary guess ;-).

        synchronized(sigLock) {
            // 信号变了, 说明有新的调度,调度器不能被释放了
            if (!isScheduleChanged())
                return false;

            boolean earlier = false;

            if(getSignaledNextFireTime() == 0)
                earlier = true;
            else if(getSignaledNextFireTime() < oldTime )
                // 发生了提前调度了
                earlier = true;

            if(earlier) {
                // so the new time is considered earlier, but is it enough earlier?
                long diff = oldTime - System.currentTimeMillis();
                // 相差小于7-70毫秒都不算提前执行
                if(diff < (qsRsrcs.getJobStore().supportsPersistence() ? 70L : 7L))
                    earlier = false;
            }

            // 重置调度信号= false
            if(clearSignal) {
                clearSignaledSchedulingChange();
            }

            return earlier;
        }
    }

    public Logger getLog() {
        return log;
    }

} // end of QuartzSchedulerThread
