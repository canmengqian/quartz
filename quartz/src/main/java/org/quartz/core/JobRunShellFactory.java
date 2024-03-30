
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

import org.quartz.Scheduler;
import org.quartz.SchedulerConfigException;
import org.quartz.SchedulerException;
import org.quartz.spi.TriggerFiredBundle;

/**
 * <p>
 * Responsible for creating the instances of <code>{@link JobRunShell}</code>
 * to be used within the <code>{@link QuartzScheduler}</code> instance.
 * </p>
 * 
 * @author James House
 */

/**
 * JobRunShellFactory 在 Quartz 定时任务框架中扮演着关键角色，它的主要作用是负责创建 JobRunShell 对象。以下是它的具体功能：
 * 创建Job执行容器：当 Quartz 调度器确定某个 Job 应该被执行时，它需要一个能够封装 Job 执行上下文和处理运行时逻辑的对象，这个对象就是由 JobRunShellFactory 创建的 JobRunShell。
 * 封装运行时环境：JobRunShell 提供了执行 Job 时的安全环境，它可以管理与 Job 执行相关的资源，如线程、事务等，并确保即使在多线程环境下也能正确地执行和完成 Job。
 * 异常处理：在 JobRunShell 中，会捕获 Job 执行过程中可能抛出的异常，进行适当的处理，并确保调度器的状态得到正确的更新。
 * 初始化上下文：JobRunShell 实例化时会构造 JobExecutionContext，这个上下文对象包含了 Job 执行所需的所有相关信息，并作为参数传递给 Job 的 execute 方法。
 * 集成外部服务：通过自定义的 JobRunShellFactory 实现，开发者还可以灵活地集成诸如事务管理、安全管理或其他框架服务到 Job 的执行过程中。
 * 总之，JobRunShellFactory 是 Quartz 框架用于生产执行 Job 所需运行环境的工厂类，是连接调度逻辑与实际业务逻辑执行的关键桥梁
 */
public interface JobRunShellFactory {

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Interface.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * Initialize the factory, providing a handle to the <code>Scheduler</code>
     * that should be made available within the <code>JobRunShell</code> and
     * the <code>JobExecutionContext</code> s within it.
     * </p>
     */
    void initialize(Scheduler scheduler)
        throws SchedulerConfigException;

    /**
     * <p>
     * Called by the <code>{@link org.quartz.core.QuartzSchedulerThread}</code>
     * to obtain instances of <code>{@link JobRunShell}</code>.
     * </p>
     */
    JobRunShell createJobRunShell(TriggerFiredBundle bundle) throws SchedulerException;
}