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

- 代码作用

```java
InitialContext ic = null; 
        try {
            ic = new InitialContext(); 
            TransactionManager tm = (TransactionManager)ic.lookup(transactionManagerJNDIName);
            
            return tm.getTransaction();
        }
```

