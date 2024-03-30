package org.quartz.core;

public interface SampledStatistics {
    /**
     * 该函数的功能是获取最近一次采样时调度的作业数量。
     * 具体来说，它返回一个长整型数值，
     * 代表了在最近一次采样时被调度的作业的数量。
     * 这个函数可以用于监控和统计作业调度系统的性能和负载情况。
     * @return
     */
    long getJobsScheduledMostRecentSample();
    /**
     * 该函数的功能是获取最近一次采样时正在执行的作业数量。
     * 具体来说，它返回一个长整型数值，
     * 代表了在最近一次采样时正在执行的作业的数量。
     * 这个函数可以用于监控和统计作业调度系统的性能和负载情况。
     * @return
     */
    long getJobsExecutingMostRecentSample();
    /**
     * 该函数的功能是获取最近一次采样时已完成的作业数量。
     * 具体来说，它返回一个长整型数值，
     * 代表了在最近一次采样时已完成的作业的数量。
     * 这个函数可以用于监控和统计作业调度系统的性能和负载情况。
     * @return
     */

    long getJobsCompletedMostRecentSample();
    void shutdown();
}
