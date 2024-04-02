[TOC]

## JTANonClusteredSemaphore

### SemaphoreSynchronization

- 是有什么的作用？

```java
Transaction t = getTransaction();
            if (t != null) {
                try {
                    // TODO 同步锁？
                    t.registerSynchronization(new SemaphoreSynchronization(lockName));
                } catch (Exception e) {
                    throw new LockException("Failed to register semaphore with Transaction.", e);
                }
            }
```

### InitialContext

javax.naming.InitialContext 类在 Java 的事务管理中扮演了查找和绑定事务相关资源的角色。在支持 JTA（Java Transaction API）的环境中，比如 Java EE 应用服务器（如 WebLogic、WebSphere 或 WildFly 等），InitialContext 被用来定位全局事务协调器和其他事务相关的服务。
具体来说，在涉及事务处理的场景中，InitialContext 主要用于以下几个方面：

- 查找事务协调器：
  开发者可以通过 InitialContext 查找并实例化 UserTransaction 接口的实现，这个接口提供了开启、提交和回滚事务的方法。例如，以下代码片段展示了如何通过 InitialContext 获取 UserTransaction 实例以便进行事务管理：

- 查找数据源和JMS资源：
  在分布式事务中，InitialContext 还可以用来查找和获取数据源（DataSource）以及其他参与事务的资源，比如 JMS 队列和主题（Queue and Topic）。这些资源通常会参与到全局事务中，保证它们的操作也能按照事务的ACID属性（原子性、一致性、隔离性和持久性）来执行。

- 环境配置：
  InitialContext 构造时可以接收一个环境参数，用于配置应用程序与命名和目录服务之间的连接以及相关事务服务的定位细节。
  总之，在Java事务管理中，InitialContext 是用来定位和绑定事务服务的关键入口点，它使得应用程序能够与底层的事务基础设施进行交互，从而实现对事务的高级控制。

  ```java
     Context ctx = new InitialContext();
     UserTransaction utx = (UserTransaction) ctx.lookup("java:comp/UserTransaction");
     utx.begin();
     // 执行一系列数据库操作...
     utx.commit();
     
  ```

  

代码作用

```java
InitialContext ic = null; 
        try {
            ic = new InitialContext(); 
            TransactionManager tm = (TransactionManager)ic.lookup(transactionManagerJNDIName);
            
            return tm.getTransaction();
        }
```

