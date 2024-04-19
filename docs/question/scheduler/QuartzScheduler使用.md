# QuartzScheduler草稿

## start()方法启动流程

### 启动流程

-  已经关停或关闭则不允许重新启动
-  发出Scheduler正在启动的事件
-  判断是否是初始化启动,如果是第一次启动,那么会调用JobStore的schedulerStarted()方法完成一些初始化. 基于JobStoreSupport的数据库持久化就做了以下操作
	-   如果是集群模式的化,实例化一个集群管理器ClusterManager,这个类本身也是一个线程,完成整个集群的初始化【需要单开一个章节】， 单机模式则需要从数据库里恢复所有的job， LOCK_TRIGGER_ACCESS有关
	-   完成MisfireHandler 初始化


### 方法作用