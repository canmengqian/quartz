
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

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.quartz.Calendar;
import org.quartz.InterruptableJob;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.JobListener;
import org.quartz.ListenerManager;
import org.quartz.Matcher;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerContext;
import org.quartz.SchedulerException;
import org.quartz.SchedulerListener;
import org.quartz.SchedulerMetaData;
import org.quartz.Trigger;
import static org.quartz.TriggerBuilder.*;
import org.quartz.TriggerKey;
import org.quartz.TriggerListener;
import org.quartz.UnableToInterruptJobException;
import org.quartz.Trigger.CompletedExecutionInstruction;
import org.quartz.Trigger.TriggerState;
import org.quartz.core.jmx.QuartzSchedulerMBean;
import org.quartz.impl.SchedulerRepository;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.listeners.SchedulerListenerSupport;
import org.quartz.simpl.PropertySettingJobFactory;
import org.quartz.spi.JobFactory;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.SchedulerPlugin;
import org.quartz.spi.SchedulerSignaler;
import org.quartz.spi.ThreadExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * This is the heart of Quartz, an indirect implementation of the <code>{@link org.quartz.Scheduler}</code>
 * interface, containing methods to schedule <code>{@link org.quartz.Job}</code>s,
 * register <code>{@link org.quartz.JobListener}</code> instances, etc.
 * </p>
 * 
 * @see org.quartz.Scheduler
 * @see org.quartz.core.QuartzSchedulerThread
 * @see org.quartz.spi.JobStore
 * @see org.quartz.spi.ThreadPool
 * 
 * @author James House
 */
public class QuartzScheduler implements RemotableQuartzScheduler {

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Constants.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    private static String VERSION_MAJOR = "UNKNOWN";
    private static String VERSION_MINOR = "UNKNOWN";
    private static String VERSION_ITERATION = "UNKNOWN";

    // 获取版本信息
    static {
        Properties props = new Properties();
        InputStream is = null;
        try {
            is = QuartzScheduler.class.getResourceAsStream("quartz-build.properties");
            if(is != null) {
                props.load(is);
                String version = props.getProperty("version");
                if (version != null) {
                    String[] versionComponents = version.split("\\.");
                    VERSION_MAJOR = versionComponents[0];
                    VERSION_MINOR = versionComponents[1];
                    if(versionComponents.length > 2)
                        VERSION_ITERATION = versionComponents[2];
                    else
                        VERSION_ITERATION = "0";
                } else {
                  (LoggerFactory.getLogger(QuartzScheduler.class)).error(
                      "Can't parse Quartz version from quartz-build.properties");
                }
            }
        } catch (Exception e) {
            (LoggerFactory.getLogger(QuartzScheduler.class)).error(
                "Error loading version info from quartz-build.properties.", e);
        } finally {
            if(is != null) {
                try { is.close(); } catch(Exception ignore) {}
            }
        }
    }
    

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Data members.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    private QuartzSchedulerResources resources;

    private QuartzSchedulerThread schedThread;

    private ThreadGroup threadGroup;

    private SchedulerContext context = new SchedulerContext();

    // 监听器管理器
    private ListenerManager listenerManager = new ListenerManagerImpl();
    // 内部job监听器
    private HashMap<String, JobListener> internalJobListeners = new HashMap<String, JobListener>(10);

    // 内部触发器监听器
    private HashMap<String, TriggerListener> internalTriggerListeners = new HashMap<String, TriggerListener>(10);

    // 内部调度器监听器
    private ArrayList<SchedulerListener> internalSchedulerListeners = new ArrayList<SchedulerListener>(10);

    private JobFactory jobFactory = new PropertySettingJobFactory();

    // 正在执行的任务管理器
    ExecutingJobsManager jobMgr = null;

    ErrorLogger errLogger = null;

    private SchedulerSignaler signaler;

    private Random random = new Random();

    // 防止GC
    private ArrayList<Object> holdToPreventGC = new ArrayList<Object>(5);

    private boolean signalOnSchedulingChange = true;

    // 当前调度器是否已经关闭
    private volatile boolean closed = false;//
    private volatile boolean shuttingDown = false;
    private boolean boundRemotely = false;

    private QuartzSchedulerMBean jmxBean = null;
    
    private Date initialStart = null;

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    // private static final Map<String, ManagementServer> MGMT_SVR_BY_BIND = new
    // HashMap<String, ManagementServer>();
    // private String registeredManagementServerBind;

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Constructors.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * Create a <code>QuartzScheduler</code> with the given configuration
     * properties.
     * </p>
     * 
     * @see QuartzSchedulerResources
     */
    public QuartzScheduler(QuartzSchedulerResources resources, long idleWaitTime, @Deprecated long dbRetryInterval)
        throws SchedulerException {
        this.resources = resources;
        // 将jobStore注册为内部jobListener
        if (resources.getJobStore() instanceof JobListener) {
            addInternalJobListener((JobListener)resources.getJobStore());
        }

        // 创建调度线程
        this.schedThread = new QuartzSchedulerThread(this, resources);
        // 创建线程执行器
        ThreadExecutor schedThreadExecutor = resources.getThreadExecutor();
        // 启动trigger调度线程
        schedThreadExecutor.execute(this.schedThread);
        if (idleWaitTime > 0) {
            // 设置调度线程空闲等待时间
            this.schedThread.setIdleWaitTime(idleWaitTime);
        }
        // 创建正在执行的任务管理器
        jobMgr = new ExecutingJobsManager();
        // 将正在执行的任务管理器注册为内部jobListener
        addInternalJobListener(jobMgr);
        // 创建错误日志监听器
        errLogger = new ErrorLogger();
        // 将错误日志监听器注册为内部调度器监听器
        addInternalSchedulerListener(errLogger);
        // 创建调度器信号器
        signaler = new SchedulerSignalerImpl(this, this.schedThread);
        
        getLog().info("Quartz Scheduler v" + getVersion() + " created.");
    }

    public void initialize() throws SchedulerException {
        
        try {
            bind();
        } catch (Exception re) {
            throw new SchedulerException(
                    "Unable to bind scheduler to RMI Registry.", re);
        }
        
        if (resources.getJMXExport()) {
            try {
                registerJMX();
            } catch (Exception e) {
                throw new SchedulerException(
                        "Unable to register scheduler with MBeanServer.", e);
            }
        }

        // ManagementRESTServiceConfiguration managementRESTServiceConfiguration
        // = resources.getManagementRESTServiceConfiguration();
        //
        // if (managementRESTServiceConfiguration != null &&
        // managementRESTServiceConfiguration.isEnabled()) {
        // try {
        // /**
        // * ManagementServer will only be instantiated and started if one
        // * isn't already running on the configured port for this class
        // * loader space.
        // */
        // synchronized (QuartzScheduler.class) {
        // if
        // (!MGMT_SVR_BY_BIND.containsKey(managementRESTServiceConfiguration.getBind()))
        // {
        // Class<?> managementServerImplClass =
        // Class.forName("org.quartz.management.ManagementServerImpl");
        // Class<?> managementRESTServiceConfigurationClass[] = new Class[] {
        // managementRESTServiceConfiguration.getClass() };
        // Constructor<?> managementRESTServiceConfigurationConstructor =
        // managementServerImplClass
        // .getConstructor(managementRESTServiceConfigurationClass);
        // Object arglist[] = new Object[] { managementRESTServiceConfiguration
        // };
        // ManagementServer embeddedRESTServer = ((ManagementServer)
        // managementRESTServiceConfigurationConstructor.newInstance(arglist));
        // embeddedRESTServer.start();
        // MGMT_SVR_BY_BIND.put(managementRESTServiceConfiguration.getBind(),
        // embeddedRESTServer);
        // }
        // registeredManagementServerBind =
        // managementRESTServiceConfiguration.getBind();
        // ManagementServer embeddedRESTServer =
        // MGMT_SVR_BY_BIND.get(registeredManagementServerBind);
        // embeddedRESTServer.register(this);
        // }
        // } catch (Exception e) {
        // throw new
        // SchedulerException("Unable to start the scheduler management REST service",
        // e);
        // }
        // }

        
        getLog().info("Scheduler meta-data: " +
                (new SchedulerMetaData(getSchedulerName(),
                        getSchedulerInstanceId(), getClass(), boundRemotely, runningSince() != null, 
                        isInStandbyMode(), isShutdown(), runningSince(), 
                        numJobsExecuted(), getJobStoreClass(), 
                        supportsPersistence(), isClustered(), getThreadPoolClass(), 
                        getThreadPoolSize(), getVersion())).toString());
    }
    
    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Interface.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    public String getVersion() {
        return getVersionMajor() + "." + getVersionMinor() + "."
                + getVersionIteration();
    }

    public static String getVersionMajor() {
        return VERSION_MAJOR;
    }
    
    public static String getVersionMinor() {
        return VERSION_MINOR;
    }

    public static String getVersionIteration() {
        return VERSION_ITERATION;
    }

    public SchedulerSignaler getSchedulerSignaler() {
        return signaler;
    }

    public Logger getLog() {
        return log;
    }
    
    /**
     * Register the scheduler in the local MBeanServer.
     */
    private void registerJMX() throws Exception {
        String jmxObjectName = resources.getJMXObjectName();
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        jmxBean = new QuartzSchedulerMBeanImpl(this);
        mbs.registerMBean(jmxBean, new ObjectName(jmxObjectName));
    }

    /**
     * Unregister the scheduler from the local MBeanServer.
     */
    private void unregisterJMX() throws Exception {
        String jmxObjectName = resources.getJMXObjectName();
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        mbs.unregisterMBean(new ObjectName(jmxObjectName));
        jmxBean.setSampledStatisticsEnabled(false);
        getLog().info("Scheduler unregistered from name '" + jmxObjectName + "' in the local MBeanServer.");
    }

    /**
     * <p>
     * Bind the scheduler to an RMI registry.
     * </p>
     */
    private void bind() throws RemoteException {
        String host = resources.getRMIRegistryHost();
        // don't export if we're not configured to do so...
        if (host == null || host.length() == 0) {
            return;
        }

        RemotableQuartzScheduler exportable = null;

        if(resources.getRMIServerPort() > 0) {
            exportable = (RemotableQuartzScheduler) UnicastRemoteObject
                .exportObject(this, resources.getRMIServerPort());
        } else {
            exportable = (RemotableQuartzScheduler) UnicastRemoteObject
                .exportObject(this, 1099);
        }

        Registry registry = null;

        if (resources.getRMICreateRegistryStrategy().equals(
                QuartzSchedulerResources.CREATE_REGISTRY_AS_NEEDED)) {
            try {
                // First try to get an existing one, instead of creating it,
                // since if
                // we're in a web-app being 'hot' re-depoloyed, then the JVM
                // still
                // has the registry that we created above the first time...
                registry = LocateRegistry.getRegistry(resources
                        .getRMIRegistryPort());
                registry.list();
            } catch (Exception e) {
                registry = LocateRegistry.createRegistry(resources
                        .getRMIRegistryPort());
            }
        } else if (resources.getRMICreateRegistryStrategy().equals(
                QuartzSchedulerResources.CREATE_REGISTRY_ALWAYS)) {
            try {
                registry = LocateRegistry.createRegistry(resources
                        .getRMIRegistryPort());
            } catch (Exception e) {
                // Fall back to an existing one, instead of creating it, since
                // if
                // we're in a web-app being 'hot' re-depoloyed, then the JVM
                // still
                // has the registry that we created above the first time...
                registry = LocateRegistry.getRegistry(resources
                        .getRMIRegistryPort());
            }
        } else {
            registry = LocateRegistry.getRegistry(resources
                    .getRMIRegistryHost(), resources.getRMIRegistryPort());
        }

        String bindName = resources.getRMIBindName();
        
        registry.rebind(bindName, exportable);
        
        boundRemotely = true;

        getLog().info("Scheduler bound to RMI registry under name '" + bindName + "'");
    }

    /**
     * <p>
     * Un-bind the scheduler from an RMI registry.
     * </p>
     */
    private void unBind() throws RemoteException {
        String host = resources.getRMIRegistryHost();
        // don't un-export if we're not configured to do so...
        if (host == null || host.length() == 0) {
            return;
        }

        Registry registry = LocateRegistry.getRegistry(resources
                .getRMIRegistryHost(), resources.getRMIRegistryPort());

        String bindName = resources.getRMIBindName();
        
        try {
            registry.unbind(bindName);
            UnicastRemoteObject.unexportObject(this, true);
        } catch (java.rmi.NotBoundException nbe) {
        }

        getLog().info("Scheduler un-bound from name '" + bindName + "' in RMI registry");
    }

    /**
     * <p>
     * Returns the name of the <code>QuartzScheduler</code>.
     * </p>
     */
    public String getSchedulerName() {
        return resources.getName();
    }

    /**
     * <p>
     * Returns the instance Id of the <code>QuartzScheduler</code>.
     * </p>
     */
    public String getSchedulerInstanceId() {
        return resources.getInstanceId();
    }

    /**
     * <p>
     * Returns the name of the thread group for Quartz's main threads.
     * </p>
     */
    public ThreadGroup getSchedulerThreadGroup() {
        if (threadGroup == null) {
            threadGroup = new ThreadGroup("QuartzScheduler:"
                    + getSchedulerName());
            if (resources.getMakeSchedulerThreadDaemon()) {
                threadGroup.setDaemon(true);
            }
        }

        return threadGroup;
    }

    public void addNoGCObject(Object obj) {
        holdToPreventGC.add(obj);
    }

    public boolean removeNoGCObject(Object obj) {
        return holdToPreventGC.remove(obj);
    }

    /**
     * <p>
     * Returns the <code>SchedulerContext</code> of the <code>Scheduler</code>.
     * </p>
     */
    public SchedulerContext getSchedulerContext() throws SchedulerException {
        return context;
    }

    public boolean isSignalOnSchedulingChange() {
        return signalOnSchedulingChange;
    }

    public void setSignalOnSchedulingChange(boolean signalOnSchedulingChange) {
        this.signalOnSchedulingChange = signalOnSchedulingChange;
    }

    ///////////////////////////////////////////////////////////////////////////
    ///
    /// Scheduler State Management Methods
    ///
    ///////////////////////////////////////////////////////////////////////////

    /**
     * <p>
     * Starts the <code>QuartzScheduler</code>'s threads that fire <code>{@link org.quartz.Trigger}s</code>.
     * </p>
     * 
     * <p>
     * All <code>{@link org.quartz.Trigger}s</code> that have misfired will
     * be passed to the appropriate TriggerListener(s).
     * </p>
     */
    public void start() throws SchedulerException {

        //已经关停或关闭则不允许重新启动
        if (shuttingDown|| closed) {
            throw new SchedulerException(
                    "The Scheduler cannot be restarted after shutdown() has been called.");
        }

        // QTZ-212 : calling new schedulerStarting() method on the listeners
        // right after entering start()
        //发出启动事件
        notifySchedulerListenersStarting();

        if (initialStart == null) {
            initialStart = new Date();
            // 启动调度器
            this.resources.getJobStore().schedulerStarted();
            // 启动插件
            startPlugins();
        } else {
            // 恢复调度器
            resources.getJobStore().schedulerResumed();
        }

        schedThread.togglePause(false);

        getLog().info(
                "Scheduler " + resources.getUniqueIdentifier() + " started.");
        
        notifySchedulerListenersStarted();
    }

    public void startDelayed(final int seconds) throws SchedulerException
    {
        // 延迟指定的秒数启动
        if (shuttingDown || closed) {
            throw new SchedulerException(
                    "The Scheduler cannot be restarted after shutdown() has been called.");
        }

        Thread t = new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(seconds * 1000L); }
                catch(InterruptedException ignore) {}
                try { start(); }
                catch(SchedulerException se) {
                    getLog().error("Unable to start scheduler after startup delay.", se);
                }
            }
        });
        t.start();
    }

    /**
     * <p>
     * Temporarily halts the <code>QuartzScheduler</code>'s firing of <code>{@link org.quartz.Trigger}s</code>.
     * </p>
     * 
     * <p>
     * The scheduler is not destroyed, and can be re-started at any time.
     * </p>
     */
    public void standby() {
        // 暂停调度器
        resources.getJobStore().schedulerPaused();
        schedThread.togglePause(true);
        getLog().info(
                "Scheduler " + resources.getUniqueIdentifier() + " paused.");
        notifySchedulerListenersInStandbyMode();        
    }

    /**
     * <p>
     * Reports whether the <code>Scheduler</code> is paused.
     * </p>
     */
    public boolean isInStandbyMode() {
        // 待机模式
        return schedThread.isPaused();
    }

    public Date runningSince() {
        if(initialStart == null)
            return null;
        return new Date(initialStart.getTime());
    }

    public int numJobsExecuted() {
        return jobMgr.getNumJobsFired();
    }

    public Class<?> getJobStoreClass() {
        return resources.getJobStore().getClass();
    }

    public boolean supportsPersistence() {
        return resources.getJobStore().supportsPersistence();
    }

    public boolean isClustered() {
        return resources.getJobStore().isClustered();
    }

    public Class<?> getThreadPoolClass() {
        return resources.getThreadPool().getClass();
    }

    public int getThreadPoolSize() {
        return resources.getThreadPool().getPoolSize();
    }

    /**
     * <p>
     * Halts the <code>QuartzScheduler</code>'s firing of <code>{@link org.quartz.Trigger}s</code>,
     * and cleans up all resources associated with the QuartzScheduler.
     * Equivalent to <code>shutdown(false)</code>.
     * </p>
     * 
     * <p>
     * The scheduler cannot be re-started.
     * </p>
     */
    public void shutdown() {
        shutdown(false);
    }

    /**
     * <p>
     * Halts the <code>QuartzScheduler</code>'s firing of <code>{@link org.quartz.Trigger}s</code>,
     * and cleans up all resources associated with the QuartzScheduler.
     * </p>
     * 
     * <p>
     * The scheduler cannot be re-started.
     * </p>
     * 
     * @param waitForJobsToComplete
     *          if <code>true</code> the scheduler will not allow this method
     *          to return until all currently executing jobs have completed.
     */
    public void shutdown(boolean waitForJobsToComplete) {
        
        if(shuttingDown || closed) {
            return;
        }
        
        shuttingDown = true;

        getLog().info(
                "Scheduler " + resources.getUniqueIdentifier()
                        + " shutting down.");
        // boolean removeMgmtSvr = false;
        // if (registeredManagementServerBind != null) {
        // ManagementServer standaloneRestServer =
        // MGMT_SVR_BY_BIND.get(registeredManagementServerBind);
        //
        // try {
        // standaloneRestServer.unregister(this);
        //
        // if (!standaloneRestServer.hasRegistered()) {
        // removeMgmtSvr = true;
        // standaloneRestServer.stop();
        // }
        // } catch (Exception e) {
        // getLog().warn("Failed to shutdown the ManagementRESTService", e);
        // } finally {
        // if (removeMgmtSvr) {
        // MGMT_SVR_BY_BIND.remove(registeredManagementServerBind);
        // }
        //
        // registeredManagementServerBind = null;
        // }
        // }

        // 先待机
        standby();
        // 根据配置，停止线程池
        schedThread.halt(waitForJobsToComplete);
        
        notifySchedulerListenersShuttingdown();
        
        if( (resources.isInterruptJobsOnShutdown() && !waitForJobsToComplete) || 
                (resources.isInterruptJobsOnShutdownWithWait() && waitForJobsToComplete)) {
            List<JobExecutionContext> jobs = getCurrentlyExecutingJobs();
            for(JobExecutionContext job: jobs) {
                if(job.getJobInstance() instanceof InterruptableJob)
                    try {
                        ((InterruptableJob)job.getJobInstance()).interrupt();
                    } catch (Throwable e) {
                        // do nothing, this was just a courtesy effort
                        getLog().warn("Encountered error when interrupting job {} during shutdown: {}", job.getJobDetail().getKey(), e);
                    }
            }
        }
        // 停止线程池
        resources.getThreadPool().shutdown(waitForJobsToComplete);
        
        closed = true;

        if (resources.getJMXExport()) {
            try {
                unregisterJMX();
            } catch (Exception e) {
            }
        }

        if(boundRemotely) {
            try {
                unBind();
            } catch (RemoteException re) {
            }
        }
        // 停止插件
        shutdownPlugins();

        // 停止存储器
        resources.getJobStore().shutdown();

        notifySchedulerListenersShutdown();

        // 移除调度器
        SchedulerRepository.getInstance().remove(resources.getName());

        holdToPreventGC.clear();
        
        getLog().info(
                "Scheduler " + resources.getUniqueIdentifier()
                        + " shutdown complete.");
    }

    /**
     * <p>
     * Reports whether the <code>Scheduler</code> has been shutdown.
     * </p>
     */
    public boolean isShutdown() {
        return closed;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public boolean isStarted() {
        return !shuttingDown && !closed && !isInStandbyMode() && initialStart != null;
    }
    
    public void validateState() throws SchedulerException {
        if (isShutdown()) {
            throw new SchedulerException("The Scheduler has been shutdown.");
        }

        // other conditions to check (?)
    }

    /**
     * <p>
     * Return a list of <code>JobExecutionContext</code> objects that
     * represent all currently executing Jobs in this Scheduler instance.
     * </p>
     * 
     * <p>
     * This method is not cluster aware.  That is, it will only return Jobs
     * currently executing in this Scheduler instance, not across the entire
     * cluster.
     * </p>
     * 
     * <p>
     * Note that the list returned is an 'instantaneous' snap-shot, and that as
     * soon as it's returned, the true list of executing jobs may be different.
     * </p>
     */
    public List<JobExecutionContext> getCurrentlyExecutingJobs() {
        return jobMgr.getExecutingJobs();
    }

    ///////////////////////////////////////////////////////////////////////////
    ///
    /// Scheduling-related Methods
    ///
    ///////////////////////////////////////////////////////////////////////////

    /**
     * <p>
     * Add the <code>{@link org.quartz.Job}</code> identified by the given
     * <code>{@link org.quartz.JobDetail}</code> to the Scheduler, and
     * associate the given <code>{@link org.quartz.Trigger}</code> with it.
     * </p>
     * 
     * <p>
     * If the given Trigger does not reference any <code>Job</code>, then it
     * will be set to reference the Job passed with it into this method.
     * </p>
     * 
     * @throws SchedulerException
     *           if the Job or Trigger cannot be added to the Scheduler, or
     *           there is an internal Scheduler error.
     */
    public Date scheduleJob(JobDetail jobDetail,
            Trigger trigger) throws SchedulerException {
        validateState();

        if (jobDetail == null) {
            throw new SchedulerException("JobDetail cannot be null");
        }
        
        if (trigger == null) {
            throw new SchedulerException("Trigger cannot be null");
        }
        
        if (jobDetail.getKey() == null) {
            throw new SchedulerException("Job's key cannot be null");
        }

        if (jobDetail.getJobClass() == null) {
            throw new SchedulerException("Job's class cannot be null");
        }
        // 转化为
        OperableTrigger trig = (OperableTrigger)trigger;

        if (trigger.getJobKey() == null) {
            trig.setJobKey(jobDetail.getKey());
        } else if (!trigger.getJobKey().equals(jobDetail.getKey())) {
            throw new SchedulerException(
                "Trigger does not reference given job!");
        }
        // 验证触发器合理性

        trig.validate();

        Calendar cal = null;
        if (trigger.getCalendarName() != null) {
            cal = resources.getJobStore().retrieveCalendar(trigger.getCalendarName());
        }
        // 计算触发器第一次执行时间
        Date ft = trig.computeFirstFireTime(cal);

        if (ft == null) {
            throw new SchedulerException(
                    "Based on configured schedule, the given trigger '" + trigger.getKey() + "' will never fire.");
        }

        // 存储JOB和 TRIGGER
        resources.getJobStore().storeJobAndTrigger(jobDetail, trig);
        // 通知监听器JOB已经添加
        notifySchedulerListenersJobAdded(jobDetail);
        // 通知调度线程
        notifySchedulerThread(trigger.getNextFireTime().getTime());
        // 通知监听器触发器已经添加
        notifySchedulerListenersSchduled(trigger);

        return ft;
    }

    /**
     * <p>
     * Schedule the given <code>{@link org.quartz.Trigger}</code> with the
     * <code>Job</code> identified by the <code>Trigger</code>'s settings.
     * </p>
     * 
     * @throws SchedulerException
     *           if the indicated Job does not exist, or the Trigger cannot be
     *           added to the Scheduler, or there is an internal Scheduler
     *           error.
     */
    public Date scheduleJob(Trigger trigger)
        throws SchedulerException {
        validateState();

        if (trigger == null) {
            throw new SchedulerException("Trigger cannot be null");
        }

        OperableTrigger trig = (OperableTrigger)trigger;
        
        trig.validate();

        Calendar cal = null;
        if (trigger.getCalendarName() != null) {
            cal = resources.getJobStore().retrieveCalendar(trigger.getCalendarName());
            if(cal == null) {
                throw new SchedulerException(
                    "Calendar not found: " + trigger.getCalendarName());
            }
        }
        Date ft = trig.computeFirstFireTime(cal);

        if (ft == null) {
            throw new SchedulerException(
                    "Based on configured schedule, the given trigger '" + trigger.getKey() + "' will never fire.");
        }

        // 存储触发器
        resources.getJobStore().storeTrigger(trig, false);
        notifySchedulerThread(trigger.getNextFireTime().getTime());
        notifySchedulerListenersSchduled(trigger);

        return ft;
    }

    /**
     * <p>
     * Add the given <code>Job</code> to the Scheduler - with no associated
     * <code>Trigger</code>. The <code>Job</code> will be 'dormant' until
     * it is scheduled with a <code>Trigger</code>, or <code>Scheduler.triggerJob()</code>
     * is called for it.
     * </p>
     * 
     * <p>
     * The <code>Job</code> must by definition be 'durable', if it is not,
     * SchedulerException will be thrown.
     * </p>
     * 
     * @throws SchedulerException
     *           if there is an internal Scheduler error, or if the Job is not
     *           durable, or a Job with the same name already exists, and
     *           <code>replace</code> is <code>false</code>.
     */
    public void addJob(JobDetail jobDetail, boolean replace) throws SchedulerException {
        addJob(jobDetail, replace, false);
    }

    public void addJob(JobDetail jobDetail, boolean replace, boolean storeNonDurableWhileAwaitingScheduling) throws SchedulerException {
        validateState();

        if (!storeNonDurableWhileAwaitingScheduling && !jobDetail.isDurable()) {
            throw new SchedulerException(
                    "Jobs added with no trigger must be durable.");
        }
        // 存储JOB
        resources.getJobStore().storeJob(jobDetail, replace);
        notifySchedulerThread(0L);
        notifySchedulerListenersJobAdded(jobDetail);
    }

    /**
     * <p>
     * Delete the identified <code>Job</code> from the Scheduler - and any
     * associated <code>Trigger</code>s.
     * </p>
     * 
     * @return true if the Job was found and deleted.
     * @throws SchedulerException
     *           if there is an internal Scheduler error.
     */
    public boolean deleteJob(JobKey jobKey) throws SchedulerException {
        validateState();

        boolean result = false;
        // 获取JOB关联的触发器
        List<? extends Trigger> triggers = getTriggersOfJob(jobKey);
        for (Trigger trigger : triggers) {
            // 卸载触发器和JOB间的关系
            if (!unscheduleJob(trigger.getKey())) {
                StringBuilder sb = new StringBuilder().append(
                        "Unable to unschedule trigger [").append(
                        trigger.getKey()).append("] while deleting job [")
                        .append(jobKey).append(
                                "]");
                throw new SchedulerException(sb.toString());
            }
            result = true;
        }

        result = resources.getJobStore().removeJob(jobKey) || result;
        if (result) {
            notifySchedulerThread(0L);
            notifySchedulerListenersJobDeleted(jobKey);
        }
        return result;
    }

    public boolean deleteJobs(List<JobKey> jobKeys)  throws SchedulerException {
        validateState();

        boolean result = false;
        
        result = resources.getJobStore().removeJobs(jobKeys);
        notifySchedulerThread(0L);
        for(JobKey key: jobKeys)
            notifySchedulerListenersJobDeleted(key);
        return result;
    }

    public void scheduleJobs(Map<JobDetail, Set<? extends Trigger>> triggersAndJobs, boolean replace)  throws SchedulerException  {
        validateState();
        // 一个JOB对应多个Trigger
        // make sure all triggers refer to their associated job
        for(Entry<JobDetail, Set<? extends Trigger>> e: triggersAndJobs.entrySet()) {
            JobDetail job = e.getKey();
            if(job == null) // there can be one of these (for adding a bulk set of triggers for pre-existing jobs)
                continue;
            Set<? extends Trigger> triggers = e.getValue();
            if(triggers == null) // this is possible because the job may be durable, and not yet be having triggers
                continue;
            for(Trigger trigger: triggers) {
                OperableTrigger opt = (OperableTrigger)trigger;
                opt.setJobKey(job.getKey());

                opt.validate();

                Calendar cal = null;
                if (trigger.getCalendarName() != null) {
                    cal = resources.getJobStore().retrieveCalendar(trigger.getCalendarName());
                    if(cal == null) {
                        throw new SchedulerException(
                            "Calendar '" + trigger.getCalendarName() + "' not found for trigger: " + trigger.getKey());
                    }
                }
                Date ft = opt.computeFirstFireTime(cal);

                if (ft == null) {
                    throw new SchedulerException(
                            "Based on configured schedule, the given trigger will never fire.");
                }                
            }
        }

        resources.getJobStore().storeJobsAndTriggers(triggersAndJobs, replace);
        notifySchedulerThread(0L);
        for (JobDetail job : triggersAndJobs.keySet()) {
          notifySchedulerListenersJobAdded(job);

          Set<? extends Trigger> triggers = triggersAndJobs.get(job);
          for (Trigger trigger : triggers) {
            notifySchedulerListenersSchduled(trigger);
          }
        }
    }

    public void scheduleJob(JobDetail jobDetail, Set<? extends Trigger> triggersForJob,
            boolean replace) throws SchedulerException {
        // 一个JOB对应多个Trigger
        Map<JobDetail, Set<? extends Trigger>> triggersAndJobs = new HashMap<JobDetail, Set<? extends Trigger>>();
        triggersAndJobs.put(jobDetail, triggersForJob);
        scheduleJobs(triggersAndJobs, replace);
    }

    public boolean unscheduleJobs(List<TriggerKey> triggerKeys) throws SchedulerException  {
        validateState();

        boolean result = false;
        
        result = resources.getJobStore().removeTriggers(triggerKeys);
        notifySchedulerThread(0L);
        for(TriggerKey key: triggerKeys)
            notifySchedulerListenersUnscheduled(key);
        return result;
    }
    
    /**
     * <p>
     * Remove the indicated <code>{@link org.quartz.Trigger}</code> from the
     * scheduler.
     * </p>
     */
    public boolean unscheduleJob(TriggerKey triggerKey) throws SchedulerException {
        validateState();

        if (resources.getJobStore().removeTrigger(triggerKey)) {
            notifySchedulerThread(0L);
            notifySchedulerListenersUnscheduled(triggerKey);
        } else {
            return false;
        }

        return true;
    }


    /**
     * <p>
     * Remove (delete) the <code>{@link org.quartz.Trigger}</code> with the
     * given name, and store the new given one - which must be associated
     * with the same job.
     * </p>
     * @param newTrigger
     *          The new <code>Trigger</code> to be stored.
     * 
     * @return <code>null</code> if a <code>Trigger</code> with the given
     *         name and group was not found and removed from the store, otherwise
     *         the first fire time of the newly scheduled trigger.
     */
    public Date rescheduleJob(TriggerKey triggerKey,
            Trigger newTrigger) throws SchedulerException {
        validateState();

        if (triggerKey == null) {
            throw new IllegalArgumentException("triggerKey cannot be null");
        }
        if (newTrigger == null) {
            throw new IllegalArgumentException("newTrigger cannot be null");
        }

        OperableTrigger trig = (OperableTrigger)newTrigger;
        Trigger oldTrigger = getTrigger(triggerKey);
        if (oldTrigger == null) {
            return null;
        } else {
            trig.setJobKey(oldTrigger.getJobKey());
        }
        trig.validate();

        Calendar cal = null;
        if (newTrigger.getCalendarName() != null) {
            cal = resources.getJobStore().retrieveCalendar(
                    newTrigger.getCalendarName());
        }
        Date ft = trig.computeFirstFireTime(cal);

        if (ft == null) {
            throw new SchedulerException(
                    "Based on configured schedule, the given trigger will never fire.");
        }
        
        if (resources.getJobStore().replaceTrigger(triggerKey, trig)) {
            notifySchedulerThread(newTrigger.getNextFireTime().getTime());
            notifySchedulerListenersUnscheduled(triggerKey);
            notifySchedulerListenersSchduled(newTrigger);
        } else {
            return null;
        }

        return ft;
        
    }
    
    
    private String newTriggerId() {
        long r = random.nextLong();
        if (r < 0) {
            r = -r;
        }
        return "MT_"
                + Long.toString(r, 30 + (int) (System.currentTimeMillis() % 7));
    }

    /**
     * <p>
     * Trigger the identified <code>{@link org.quartz.Job}</code> (execute it
     * now) - with a non-volatile trigger.
     * </p>
     */
    @SuppressWarnings("deprecation")
    public void triggerJob(JobKey jobKey, JobDataMap data) throws SchedulerException {
        validateState();

        OperableTrigger trig = (OperableTrigger) newTrigger().withIdentity(newTriggerId(), Scheduler.DEFAULT_GROUP).forJob(jobKey).build();
        trig.computeFirstFireTime(null);
        if(data != null) {
            trig.setJobDataMap(data);
        }

        boolean collision = true;
        while (collision) {
            try {
                resources.getJobStore().storeTrigger(trig, false);
                collision = false;
            } catch (ObjectAlreadyExistsException oaee) {
                trig.setKey(new TriggerKey(newTriggerId(), Scheduler.DEFAULT_GROUP));
            }
        }

        notifySchedulerThread(trig.getNextFireTime().getTime());
        notifySchedulerListenersSchduled(trig);
    }

    /**
     * <p>
     * Store and schedule the identified <code>{@link org.quartz.spi.OperableTrigger}</code>
     * </p>
     */
    public void triggerJob(OperableTrigger trig) throws SchedulerException {
        validateState();

        trig.computeFirstFireTime(null);

        boolean collision = true;
        while (collision) {
            try {
                resources.getJobStore().storeTrigger(trig, false);
                collision = false;
            } catch (ObjectAlreadyExistsException oaee) {
                trig.setKey(new TriggerKey(newTriggerId(), Scheduler.DEFAULT_GROUP));
            }
        }

        notifySchedulerThread(trig.getNextFireTime().getTime());
        notifySchedulerListenersSchduled(trig);
    }
    
    /**
     * <p>
     * Pause the <code>{@link Trigger}</code> with the given name.
     * </p>
     *  
     */
    public void pauseTrigger(TriggerKey triggerKey) throws SchedulerException {
        validateState();

        resources.getJobStore().pauseTrigger(triggerKey);
        notifySchedulerThread(0L);
        notifySchedulerListenersPausedTrigger(triggerKey);
    }

    /**
     * <p>
     * Pause all of the <code>{@link Trigger}s</code> in the matching groups.
     * </p>
     *  
     */
    public void pauseTriggers(GroupMatcher<TriggerKey> matcher)
        throws SchedulerException {
        validateState();

        if(matcher == null) {
            matcher = GroupMatcher.groupEquals(Scheduler.DEFAULT_GROUP);
        }

        Collection<String> pausedGroups = resources.getJobStore().pauseTriggers(matcher);
        notifySchedulerThread(0L);
        for (String pausedGroup : pausedGroups) {
            notifySchedulerListenersPausedTriggers(pausedGroup);
        }
    }

    /**
     * <p>
     * Pause the <code>{@link org.quartz.JobDetail}</code> with the given
     * name - by pausing all of its current <code>Trigger</code>s.
     * </p>
     *  
     */
    public void pauseJob(JobKey jobKey) throws SchedulerException {
        validateState();

        resources.getJobStore().pauseJob(jobKey);
        notifySchedulerThread(0L);
        notifySchedulerListenersPausedJob(jobKey);
    }

    /**
     * <p>
     * Pause all of the <code>{@link org.quartz.JobDetail}s</code> in the
     * matching groups - by pausing all of their <code>Trigger</code>s.
     * </p>
     *  
     */
    public void pauseJobs(GroupMatcher<JobKey> groupMatcher)
        throws SchedulerException {
        validateState();

        if(groupMatcher == null) {
            groupMatcher = GroupMatcher.groupEquals(Scheduler.DEFAULT_GROUP);
        }
        
        Collection<String> pausedGroups = resources.getJobStore().pauseJobs(groupMatcher);
        notifySchedulerThread(0L);
        for (String pausedGroup : pausedGroups) {
            notifySchedulerListenersPausedJobs(pausedGroup);
        }
    }

    /**
     * <p>
     * Resume (un-pause) the <code>{@link Trigger}</code> with the given
     * name.
     * </p>
     * 
     * <p>
     * If the <code>Trigger</code> missed one or more fire-times, then the
     * <code>Trigger</code>'s misfire instruction will be applied.
     * </p>
     *  
     */
    public void resumeTrigger(TriggerKey triggerKey) throws SchedulerException {
        validateState();

        resources.getJobStore().resumeTrigger(triggerKey);
        notifySchedulerThread(0L);
        notifySchedulerListenersResumedTrigger(triggerKey);
    }

    /**
     * <p>
     * Resume (un-pause) all of the <code>{@link Trigger}s</code> in the
     * matching groups.
     * </p>
     * 
     * <p>
     * If any <code>Trigger</code> missed one or more fire-times, then the
     * <code>Trigger</code>'s misfire instruction will be applied.
     * </p>
     *  
     */
    public void resumeTriggers(GroupMatcher<TriggerKey> matcher)
        throws SchedulerException {
        validateState();

        if(matcher == null) {
            matcher = GroupMatcher.groupEquals(Scheduler.DEFAULT_GROUP);
        }

        Collection<String> pausedGroups = resources.getJobStore().resumeTriggers(matcher);
        notifySchedulerThread(0L);
        for (String pausedGroup : pausedGroups) {
            notifySchedulerListenersResumedTriggers(pausedGroup);
        }
    }

    public Set<String> getPausedTriggerGroups() throws SchedulerException {
        return resources.getJobStore().getPausedTriggerGroups();
    }
    
    /**
     * <p>
     * Resume (un-pause) the <code>{@link org.quartz.JobDetail}</code> with
     * the given name.
     * </p>
     * 
     * <p>
     * If any of the <code>Job</code>'s<code>Trigger</code> s missed one
     * or more fire-times, then the <code>Trigger</code>'s misfire
     * instruction will be applied.
     * </p>
     *  
     */
    public void resumeJob(JobKey jobKey) throws SchedulerException {
        validateState();

        resources.getJobStore().resumeJob(jobKey);
        notifySchedulerThread(0L);
        notifySchedulerListenersResumedJob(jobKey);
    }

    /**
     * <p>
     * Resume (un-pause) all of the <code>{@link org.quartz.JobDetail}s</code>
     * in the matching groups.
     * </p>
     * 
     * <p>
     * If any of the <code>Job</code> s had <code>Trigger</code> s that
     * missed one or more fire-times, then the <code>Trigger</code>'s
     * misfire instruction will be applied.
     * </p>
     *  
     */
    public void resumeJobs(GroupMatcher<JobKey> matcher)
        throws SchedulerException {
        validateState();

        if(matcher == null) {
            matcher = GroupMatcher.groupEquals(Scheduler.DEFAULT_GROUP);
        }
        
        Collection<String> resumedGroups = resources.getJobStore().resumeJobs(matcher);
        notifySchedulerThread(0L);
        for (String pausedGroup : resumedGroups) {
            notifySchedulerListenersResumedJobs(pausedGroup);
        }
    }

    /**
     * <p>
     * Pause all triggers - equivalent of calling <code>pauseTriggers(GroupMatcher)</code>
     * with a matcher matching all known groups.
     * </p>
     * 
     * <p>
     * When <code>resumeAll()</code> is called (to un-pause), trigger misfire
     * instructions WILL be applied.
     * </p>
     * 
     * @see #resumeAll()
     * @see #pauseTriggers(org.quartz.impl.matchers.GroupMatcher)
     * @see #standby()
     */
    public void pauseAll() throws SchedulerException {
        validateState();

        resources.getJobStore().pauseAll();
        notifySchedulerThread(0L);
        notifySchedulerListenersPausedTriggers(null);
    }

    /**
     * <p>
     * Resume (un-pause) all triggers - equivalent of calling <code>resumeTriggerGroup(group)</code>
     * on every group.
     * </p>
     * 
     * <p>
     * If any <code>Trigger</code> missed one or more fire-times, then the
     * <code>Trigger</code>'s misfire instruction will be applied.
     * </p>
     * 
     * @see #pauseAll()
     */
    public void resumeAll() throws SchedulerException {
        validateState();

        resources.getJobStore().resumeAll();
        notifySchedulerThread(0L);
        notifySchedulerListenersResumedTrigger(null);
    }

    /**
     * <p>
     * Get the names of all known <code>{@link org.quartz.Job}</code> groups.
     * </p>
     */
    public List<String> getJobGroupNames()
        throws SchedulerException {
        validateState();

        return resources.getJobStore().getJobGroupNames();
    }

    /**
     * <p>
     * Get the names of all the <code>{@link org.quartz.Job}s</code> in the
     * matching groups.
     * </p>
     */
    public Set<JobKey> getJobKeys(GroupMatcher<JobKey> matcher)
        throws SchedulerException {
        validateState();

        if(matcher == null) {
            matcher = GroupMatcher.groupEquals(Scheduler.DEFAULT_GROUP);
        }
        
        return resources.getJobStore().getJobKeys(matcher);
    }

    /**
     * <p>
     * Get all <code>{@link Trigger}</code> s that are associated with the
     * identified <code>{@link org.quartz.JobDetail}</code>.
     * </p>
     */
    public List<? extends Trigger> getTriggersOfJob(JobKey jobKey) throws SchedulerException {
        validateState();

        return resources.getJobStore().getTriggersForJob(jobKey);
    }

    /**
     * <p>
     * Get the names of all known <code>{@link org.quartz.Trigger}</code>
     * groups.
     * </p>
     */
    public List<String> getTriggerGroupNames()
        throws SchedulerException {
        validateState();

        return resources.getJobStore().getTriggerGroupNames();
    }

    /**
     * <p>
     * Get the names of all the <code>{@link org.quartz.Trigger}s</code> in
     * the matching groups.
     * </p>
     */
    public Set<TriggerKey> getTriggerKeys(GroupMatcher<TriggerKey> matcher)
        throws SchedulerException {
        validateState();

        if(matcher == null) {
            matcher = GroupMatcher.groupEquals(Scheduler.DEFAULT_GROUP);
        }
        
        return resources.getJobStore().getTriggerKeys(matcher);
    }

    /**
     * <p>
     * Get the <code>{@link JobDetail}</code> for the <code>Job</code>
     * instance with the given name and group.
     * </p>
     */
    public JobDetail getJobDetail(JobKey jobKey) throws SchedulerException {
        validateState();

        return resources.getJobStore().retrieveJob(jobKey);
    }

    /**
     * <p>
     * Get the <code>{@link Trigger}</code> instance with the given name and
     * group.
     * </p>
     */
    public Trigger getTrigger(TriggerKey triggerKey) throws SchedulerException {
        validateState();

        return resources.getJobStore().retrieveTrigger(triggerKey);
    }

    /**
     * Determine whether a {@link Job} with the given identifier already 
     * exists within the scheduler.
     * 
     * @param jobKey the identifier to check for
     * @return true if a Job exists with the given identifier
     * @throws SchedulerException 
     */
    public boolean checkExists(JobKey jobKey) throws SchedulerException {
        validateState();

        return resources.getJobStore().checkExists(jobKey);
        
    }
   
    /**
     * Determine whether a {@link Trigger} with the given identifier already 
     * exists within the scheduler.
     * 
     * @param triggerKey the identifier to check for
     * @return true if a Trigger exists with the given identifier
     * @throws SchedulerException 
     */
    public boolean checkExists(TriggerKey triggerKey) throws SchedulerException {
        validateState();

        return resources.getJobStore().checkExists(triggerKey);
        
    }
    
    /**
     * Clears (deletes!) all scheduling data - all {@link Job}s, {@link Trigger}s
     * {@link Calendar}s.
     * 
     * @throws SchedulerException
     */
    public void clear() throws SchedulerException {
        validateState();

        resources.getJobStore().clearAllSchedulingData();
        notifySchedulerListenersUnscheduled(null);
    }
    
    
    /**
     * <p>
     * Get the current state of the identified <code>{@link Trigger}</code>.
     * </p>
J     *
     * @see TriggerState
     */
    public TriggerState getTriggerState(TriggerKey triggerKey) throws SchedulerException {
        validateState();

        return resources.getJobStore().getTriggerState(triggerKey);
    }


    public void resetTriggerFromErrorState(TriggerKey triggerKey) throws SchedulerException  {
        validateState();

        resources.getJobStore().resetTriggerFromErrorState(triggerKey);
    }

    /**
     * <p>
     * Add (register) the given <code>Calendar</code> to the Scheduler.
     * </p>
     * 
     * @throws SchedulerException
     *           if there is an internal Scheduler error, or a Calendar with
     *           the same name already exists, and <code>replace</code> is
     *           <code>false</code>.
     */
    public void addCalendar(String calName, Calendar calendar, boolean replace, boolean updateTriggers) throws SchedulerException {
        validateState();

        resources.getJobStore().storeCalendar(calName, calendar, replace, updateTriggers);
    }

    /**
     * <p>
     * Delete the identified <code>Calendar</code> from the Scheduler.
     * </p>
     * 
     * @return true if the Calendar was found and deleted.
     * @throws SchedulerException
     *           if there is an internal Scheduler error.
     */
    public boolean deleteCalendar(String calName)
        throws SchedulerException {
        validateState();

        return resources.getJobStore().removeCalendar(calName);
    }

    /**
     * <p>
     * Get the <code>{@link Calendar}</code> instance with the given name.
     * </p>
     */
    public Calendar getCalendar(String calName)
        throws SchedulerException {
        validateState();

        return resources.getJobStore().retrieveCalendar(calName);
    }

    /**
     * <p>
     * Get the names of all registered <code>{@link Calendar}s</code>.
     * </p>
     */
    public List<String> getCalendarNames()
        throws SchedulerException {
        validateState();

        return resources.getJobStore().getCalendarNames();
    }

    public ListenerManager getListenerManager() {
        return listenerManager;
    }
    
    /**
     * <p>
     * Add the given <code>{@link org.quartz.JobListener}</code> to the
     * <code>Scheduler</code>'s <i>internal</i> list.
     * </p>
     */
    public void addInternalJobListener(JobListener jobListener) {
        if (jobListener.getName() == null
                || jobListener.getName().length() == 0) {
            throw new IllegalArgumentException(
                    "JobListener name cannot be empty.");
        }
        
        synchronized (internalJobListeners) {
            internalJobListeners.put(jobListener.getName(), jobListener);
        }
    }

    /**
     * <p>
     * Remove the identified <code>{@link JobListener}</code> from the <code>Scheduler</code>'s
     * list of <i>internal</i> listeners.
     * </p>
     * 
     * @return true if the identified listener was found in the list, and
     *         removed.
     */
    public boolean removeInternalJobListener(String name) {
        synchronized (internalJobListeners) {
            return (internalJobListeners.remove(name) != null);
        }
    }
    
    /**
     * <p>
     * Get a List containing all of the <code>{@link org.quartz.JobListener}</code>s
     * in the <code>Scheduler</code>'s <i>internal</i> list.
     * </p>
     */
    public List<JobListener> getInternalJobListeners() {
        synchronized (internalJobListeners) {
            return java.util.Collections.unmodifiableList(new LinkedList<JobListener>(internalJobListeners.values()));
        }
    }

    /**
     * <p>
     * Get the <i>internal</i> <code>{@link org.quartz.JobListener}</code>
     * that has the given name.
     * </p>
     */
    public JobListener getInternalJobListener(String name) {
        synchronized (internalJobListeners) {
            return internalJobListeners.get(name);
        }
    }
    
    /**
     * <p>
     * Add the given <code>{@link org.quartz.TriggerListener}</code> to the
     * <code>Scheduler</code>'s <i>internal</i> list.
     * </p>
     */
    public void addInternalTriggerListener(TriggerListener triggerListener) {
        if (triggerListener.getName() == null
                || triggerListener.getName().length() == 0) {
            throw new IllegalArgumentException(
                    "TriggerListener name cannot be empty.");
        }

        synchronized (internalTriggerListeners) {
            internalTriggerListeners.put(triggerListener.getName(), triggerListener);
        }
    }

    /**
     * <p>
     * Remove the identified <code>{@link TriggerListener}</code> from the <code>Scheduler</code>'s
     * list of <i>internal</i> listeners.
     * </p>
     * 
     * @return true if the identified listener was found in the list, and
     *         removed.
     */
    public boolean removeinternalTriggerListener(String name) {
        synchronized (internalTriggerListeners) {
            return (internalTriggerListeners.remove(name) != null);
        }
    }

    /**
     * <p>
     * Get a list containing all of the <code>{@link org.quartz.TriggerListener}</code>s
     * in the <code>Scheduler</code>'s <i>internal</i> list.
     * </p>
     */
    public List<TriggerListener> getInternalTriggerListeners() {
        synchronized (internalTriggerListeners) {
            return java.util.Collections.unmodifiableList(new LinkedList<TriggerListener>(internalTriggerListeners.values()));
        }
    }

    /**
     * <p>
     * Get the <i>internal</i> <code>{@link TriggerListener}</code> that
     * has the given name.
     * </p>
     */
    public TriggerListener getInternalTriggerListener(String name) {
        synchronized (internalTriggerListeners) {
            return internalTriggerListeners.get(name);
        }
    }

    /**
     * <p>
     * Register the given <code>{@link SchedulerListener}</code> with the
     * <code>Scheduler</code>'s list of internal listeners.
     * </p>
     */
    public void addInternalSchedulerListener(SchedulerListener schedulerListener) {
        synchronized (internalSchedulerListeners) {
            internalSchedulerListeners.add(schedulerListener);
        }
    }

    /**
     * <p>
     * Remove the given <code>{@link SchedulerListener}</code> from the
     * <code>Scheduler</code>'s list of internal listeners.
     * </p>
     * 
     * @return true if the identified listener was found in the list, and
     *         removed.
     */
    public boolean removeInternalSchedulerListener(SchedulerListener schedulerListener) {
        synchronized (internalSchedulerListeners) {
            return internalSchedulerListeners.remove(schedulerListener);
        }
    }

    /**
     * <p>
     * Get a List containing all of the <i>internal</i> <code>{@link SchedulerListener}</code>s
     * registered with the <code>Scheduler</code>.
     * </p>
     */
    public List<SchedulerListener> getInternalSchedulerListeners() {
        synchronized (internalSchedulerListeners) {
            return java.util.Collections.unmodifiableList(new ArrayList<SchedulerListener>(internalSchedulerListeners));
        }
    }

    protected void notifyJobStoreJobComplete(OperableTrigger trigger, JobDetail detail, CompletedExecutionInstruction instCode) {
        resources.getJobStore().triggeredJobComplete(trigger, detail, instCode);
    }

    protected void notifyJobStoreJobVetoed(OperableTrigger trigger, JobDetail detail, CompletedExecutionInstruction instCode) {
        resources.getJobStore().triggeredJobComplete(trigger, detail, instCode);
    }

    protected void notifySchedulerThread(long candidateNewNextFireTime) {
        if (isSignalOnSchedulingChange()) {
            signaler.signalSchedulingChange(candidateNewNextFireTime);
        }
    }

    private List<TriggerListener> buildTriggerListenerList()
        throws SchedulerException {
        List<TriggerListener> allListeners = new LinkedList<TriggerListener>();
        allListeners.addAll(getListenerManager().getTriggerListeners());
        allListeners.addAll(getInternalTriggerListeners());

        return allListeners;
    }

    private List<JobListener> buildJobListenerList()
        throws SchedulerException {
        List<JobListener> allListeners = new LinkedList<JobListener>();
        allListeners.addAll(getListenerManager().getJobListeners());
        allListeners.addAll(getInternalJobListeners());

        return allListeners;
    }

    private List<SchedulerListener> buildSchedulerListenerList() {
        List<SchedulerListener> allListeners = new LinkedList<SchedulerListener>();
        // 添加内部监听器和受管的监听器
        allListeners.addAll(getListenerManager().getSchedulerListeners());
        allListeners.addAll(getInternalSchedulerListeners());
    
        return allListeners;
    }
    
    private boolean matchJobListener(JobListener listener, JobKey key) {
        List<Matcher<JobKey>> matchers = getListenerManager().getJobListenerMatchers(listener.getName());
        if(matchers == null)
            return true;
        for(Matcher<JobKey> matcher: matchers) {
            if(matcher.isMatch(key))
                return true;
        }
        return false;
    }

    private boolean matchTriggerListener(TriggerListener listener, TriggerKey key) {
        // 根据监听器名称匹配是否存在对应的触发器
        List<Matcher<TriggerKey>> matchers = getListenerManager().getTriggerListenerMatchers(listener.getName());
        if(matchers == null)
            return true;
        for(Matcher<TriggerKey> matcher: matchers) {
            if(matcher.isMatch(key))
                return true;
        }
        return false;
    }

    public boolean notifyTriggerListenersFired(JobExecutionContext jec)
        throws SchedulerException {

        boolean vetoedExecution = false;
        
        // build a list of all trigger listeners that are to be notified...
        List<TriggerListener> triggerListeners = buildTriggerListenerList();

        // notify all trigger listeners in the list
        for(TriggerListener tl: triggerListeners) {
            try {
                if(!matchTriggerListener(tl, jec.getTrigger().getKey()))
                    continue;
                tl.triggerFired(jec.getTrigger(), jec);
                
                if(tl.vetoJobExecution(jec.getTrigger(), jec)) {
                    vetoedExecution = true;
                }
            } catch (Exception e) {
                SchedulerException se = new SchedulerException(
                        "TriggerListener '" + tl.getName()
                                + "' threw exception: " + e.getMessage(), e);
                throw se;
            }
        }
        
        return vetoedExecution;
    }
    

    public void notifyTriggerListenersMisfired(Trigger trigger)
        throws SchedulerException {
        // build a list of all trigger listeners that are to be notified...
        List<TriggerListener> triggerListeners = buildTriggerListenerList();

        // notify all trigger listeners in the list
        for(TriggerListener tl: triggerListeners) {
            try {
                if(!matchTriggerListener(tl, trigger.getKey()))
                    continue;
                tl.triggerMisfired(trigger);
            } catch (Exception e) {
                SchedulerException se = new SchedulerException(
                        "TriggerListener '" + tl.getName()
                                + "' threw exception: " + e.getMessage(), e);
                throw se;
            }
        }
    }    

    public void notifyTriggerListenersComplete(JobExecutionContext jec,
            CompletedExecutionInstruction instCode) throws SchedulerException {
        // build a list of all trigger listeners that are to be notified...
        List<TriggerListener> triggerListeners = buildTriggerListenerList();

        // notify all trigger listeners in the list
        for(TriggerListener tl: triggerListeners) {
            try {
                if(!matchTriggerListener(tl, jec.getTrigger().getKey()))
                    continue;
                tl.triggerComplete(jec.getTrigger(), jec, instCode);
            } catch (Exception e) {
                SchedulerException se = new SchedulerException(
                        "TriggerListener '" + tl.getName()
                                + "' threw exception: " + e.getMessage(), e);
                throw se;
            }
        }
    }

    public void notifyJobListenersToBeExecuted(JobExecutionContext jec)
        throws SchedulerException {
        // build a list of all job listeners that are to be notified...
        List<JobListener> jobListeners = buildJobListenerList();

        // notify all job listeners
        for(JobListener jl: jobListeners) {
            try {
                if(!matchJobListener(jl, jec.getJobDetail().getKey()))
                    continue;
                jl.jobToBeExecuted(jec);
            } catch (Exception e) {
                SchedulerException se = new SchedulerException(
                        "JobListener '" + jl.getName() + "' threw exception: "
                                + e.getMessage(), e);
                throw se;
            }
        }
    }

    public void notifyJobListenersWasVetoed(JobExecutionContext jec)
        throws SchedulerException {
        // build a list of all job listeners that are to be notified...
        List<JobListener> jobListeners = buildJobListenerList();

        // notify all job listeners
        for(JobListener jl: jobListeners) {
            try {
                if(!matchJobListener(jl, jec.getJobDetail().getKey()))
                    continue;
                jl.jobExecutionVetoed(jec);
            } catch (Exception e) {
                SchedulerException se = new SchedulerException(
                        "JobListener '" + jl.getName() + "' threw exception: "
                        + e.getMessage(), e);
                throw se;
            }
        }
    }

    public void notifyJobListenersWasExecuted(JobExecutionContext jec,
            JobExecutionException je) throws SchedulerException {
        // build a list of all job listeners that are to be notified...
        List<JobListener> jobListeners = buildJobListenerList();

        // notify all job listeners
        for(JobListener jl: jobListeners) {
            try {
                if(!matchJobListener(jl, jec.getJobDetail().getKey()))
                    continue;
                jl.jobWasExecuted(jec, je);
            } catch (Exception e) {
                SchedulerException se = new SchedulerException(
                        "JobListener '" + jl.getName() + "' threw exception: "
                                + e.getMessage(), e);
                throw se;
            }
        }
    }

    public void notifySchedulerListenersError(String msg, SchedulerException se) {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.schedulerError(msg, se);
            } catch (Exception e) {
                getLog()
                        .error(
                                "Error while notifying SchedulerListener of error: ",
                                e);
                getLog().error(
                        "  Original error (for notification) was: " + msg, se);
            }
        }
    }

    public void notifySchedulerListenersSchduled(Trigger trigger) {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.jobScheduled(trigger);
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of scheduled job."
                                + "  Triger=" + trigger.getKey(), e);
            }
        }
    }

    public void notifySchedulerListenersUnscheduled(TriggerKey triggerKey) {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                if(triggerKey == null)
                    sl.schedulingDataCleared();
                else
                    sl.jobUnscheduled(triggerKey);
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of unscheduled job."
                                + "  Triger=" + (triggerKey == null ? "ALL DATA" : triggerKey), e);
            }
        }
    }

    public void notifySchedulerListenersFinalized(Trigger trigger) {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.triggerFinalized(trigger);
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of finalized trigger."
                                + "  Triger=" + trigger.getKey(), e);
            }
        }
    }

    public void notifySchedulerListenersPausedTrigger(TriggerKey triggerKey) {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.triggerPaused(triggerKey);
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of paused trigger: "
                                + triggerKey, e);
            }
        }
    }

    public void notifySchedulerListenersPausedTriggers(String group) {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.triggersPaused(group);
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of paused trigger group."
                                + group, e);
            }
        }
    }
    
    public void notifySchedulerListenersResumedTrigger(TriggerKey key) {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.triggerResumed(key);
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of resumed trigger: "
                                + key, e);
            }
        }
    }

    public void notifySchedulerListenersResumedTriggers(String group) {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.triggersResumed(group);
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of resumed group: "
                                + group, e);
            }
        }
    }

    public void notifySchedulerListenersPausedJob(JobKey key) {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.jobPaused(key);
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of paused job: "
                                + key, e);
            }
        }
    }

    public void notifySchedulerListenersPausedJobs(String group) {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.jobsPaused(group);
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of paused job group: "
                                + group, e);
            }
        }
    }
    
    public void notifySchedulerListenersResumedJob(JobKey key) {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.jobResumed(key);
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of resumed job: "
                                + key, e);
            }
        }
    }

    public void notifySchedulerListenersResumedJobs(String group) {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.jobsResumed(group);
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of resumed job group: "
                                + group, e);
            }
        }
    }

    public void notifySchedulerListenersInStandbyMode() {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.schedulerInStandbyMode();
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of inStandByMode.",
                        e);
            }
        }
    }
    
    public void notifySchedulerListenersStarted() {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.schedulerStarted();
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of startup.",
                        e);
            }
        }
    }

    public void notifySchedulerListenersStarting() {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for (SchedulerListener sl : schedListeners) {
            try {
                sl.schedulerStarting();
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of startup.",
                        e);
            }
        }
    }

    public void notifySchedulerListenersShutdown() {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners

        for(SchedulerListener sl: schedListeners) {
            try {
                // 调度器监听器已经停止
                sl.schedulerShutdown();
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of shutdown.",
                        e);
            }
        }
    }
    
    public void notifySchedulerListenersShuttingdown() {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                // 调度器监听器正在停止
                sl.schedulerShuttingdown();
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of shutdown.",
                        e);
            }
        }
    }

    /**
     * 向SchedulerListener通知JOB被添加了
     * @param jobDetail
     */
    public void notifySchedulerListenersJobAdded(JobDetail jobDetail) {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.jobAdded(jobDetail);
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of JobAdded.",
                        e);
            }
        }
    }

    /**
     * 向SchedulerListener通知JOB被删除了
     * @param jobKey
     */
    public void notifySchedulerListenersJobDeleted(JobKey jobKey) {
        // build a list of all scheduler listeners that are to be notified...
        List<SchedulerListener> schedListeners = buildSchedulerListenerList();

        // notify all scheduler listeners
        for(SchedulerListener sl: schedListeners) {
            try {
                sl.jobDeleted(jobKey);
            } catch (Exception e) {
                getLog().error(
                        "Error while notifying SchedulerListener of JobAdded.",
                        e);
            }
        }
    }
    
    public void setJobFactory(JobFactory factory) throws SchedulerException {

        if(factory == null) {
            throw new IllegalArgumentException("JobFactory cannot be set to null!");
        }

        getLog().info("JobFactory set to: " + factory);

        this.jobFactory = factory;
    }
    
    public JobFactory getJobFactory()  {
        return jobFactory;
    }
    
    
    /**
     * 根据JobKey来中断对应的作业
     * Interrupt all instances of the identified InterruptableJob executing in 
     * this Scheduler instance.
     *  
     * <p>
     * This method is not cluster aware.  That is, it will only interrupt 
     * instances of the identified InterruptableJob currently executing in this 
     * Scheduler instance, not across the entire cluster.
     * </p>
     * 
     * @see org.quartz.core.RemotableQuartzScheduler#interrupt(JobKey)
     */
    public boolean interrupt(JobKey jobKey) throws UnableToInterruptJobException {

        List<JobExecutionContext> jobs = getCurrentlyExecutingJobs();
        
        JobDetail jobDetail = null;
        Job job = null;
        
        boolean interrupted = false;
        
        for(JobExecutionContext jec : jobs) {
            jobDetail = jec.getJobDetail();
            if (jobKey.equals(jobDetail.getKey())) {
                job = jec.getJobInstance();
                if (job instanceof InterruptableJob) {
                    ((InterruptableJob)job).interrupt();
                    interrupted = true;
                } else {
                    throw new UnableToInterruptJobException(
                            "Job " + jobDetail.getKey() +
                            " can not be interrupted, since it does not implement " +                        
                            InterruptableJob.class.getName());
                }
            }                        
        }
        
        return interrupted;
    }

    /**
     * Interrupt the identified InterruptableJob executing in this Scheduler instance.
     *  
     * <p>
     * This method is not cluster aware.  That is, it will only interrupt 
     * instances of the identified InterruptableJob currently executing in this 
     * Scheduler instance, not across the entire cluster.
     * </p>
     * 
     * @see org.quartz.core.RemotableQuartzScheduler#interrupt(JobKey)
     */
    public boolean interrupt(String fireInstanceId) throws UnableToInterruptJobException {
        // 获取当前正在执行的任务
        List<JobExecutionContext> jobs = getCurrentlyExecutingJobs();
        
        Job job = null;
        
        for(JobExecutionContext jec : jobs) {
            // 中断当前的任务,如果是不可中断的任务,则抛出异常
            if (jec.getFireInstanceId().equals(fireInstanceId)) {
                job = jec.getJobInstance();
                if (job instanceof InterruptableJob) {
                    ((InterruptableJob)job).interrupt();
                    return true;
                } else {
                    throw new UnableToInterruptJobException(
                        "Job " + jec.getJobDetail().getKey() +
                        " can not be interrupted, since it does not implement " +                        
                        InterruptableJob.class.getName());
                }
            }                        
        }
        
        return false;
    }

    // 关闭插件
    private void shutdownPlugins() {
        java.util.Iterator<SchedulerPlugin> itr = resources.getSchedulerPlugins().iterator();
        while (itr.hasNext()) {
            SchedulerPlugin plugin = itr.next();
            plugin.shutdown();
        }
    }

    private void startPlugins() {
        // 启动插件
        java.util.Iterator<SchedulerPlugin> itr = resources.getSchedulerPlugins().iterator();
        while (itr.hasNext()) {
            SchedulerPlugin plugin = itr.next();
            plugin.start();
        }
    }

}

/////////////////////////////////////////////////////////////////////////////
//
// ErrorLogger - Scheduler Listener Class
//
/////////////////////////////////////////////////////////////////////////////

class ErrorLogger extends SchedulerListenerSupport {
    ErrorLogger() {
    }
    
    @Override
    public void schedulerError(String msg, SchedulerException cause) {
        getLog().error(msg, cause);
    }

}

/////////////////////////////////////////////////////////////////////////////
//
// ExecutingJobsManager - Job Listener Class
//
/////////////////////////////////////////////////////////////////////////////

class ExecutingJobsManager implements JobListener {
    HashMap<String, JobExecutionContext> executingJobs = new HashMap<String, JobExecutionContext>();

    AtomicInteger numJobsFired = new AtomicInteger(0);

    ExecutingJobsManager() {
    }

    public String getName() {
        return getClass().getName();
    }

    public int getNumJobsCurrentlyExecuting() {
        // 获取当前正在执行的任务个数
        synchronized (executingJobs) {
            return executingJobs.size();
        }
    }

    public void jobToBeExecuted(JobExecutionContext context) {
        numJobsFired.incrementAndGet();
        // 添加当前执行的作业到当前正在执行的作业列表中
        synchronized (executingJobs) {
            executingJobs
                    .put(((OperableTrigger)context.getTrigger()).getFireInstanceId(), context);
        }
    }

    public void jobWasExecuted(JobExecutionContext context,
            JobExecutionException jobException) {
        // 移除执行的任务转为已经执行完毕的左业
        synchronized (executingJobs) {
            executingJobs.remove(((OperableTrigger)context.getTrigger()).getFireInstanceId());
        }
    }

    public int getNumJobsFired() {
        // 获取当前已经触发的任务
        return numJobsFired.get();
    }

    public List<JobExecutionContext> getExecutingJobs() {
        // 获取当前正在执行的任务
        synchronized (executingJobs) {
            return java.util.Collections.unmodifiableList(new ArrayList<JobExecutionContext>(
                    executingJobs.values()));
        }
    }

    public void jobExecutionVetoed(JobExecutionContext context) {
        
    }
}