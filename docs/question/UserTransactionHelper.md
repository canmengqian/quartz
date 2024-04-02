## UserTransactionHelper
### UserTransaction 有什么作用？
javax.transaction.UserTransaction 是 Java Transaction API (JTA) 中的一个核心接口，它主要用于分布式事务处理环境中的编程式事务管理。在企业级应用中，尤其是那些需要访问多个数据库资源或与 JMS 等其他事务性资源交互的应用程序，UserTransaction 接口提供了对事务边界控制的能力，允许开发人员手动控制事务的开始、提交和回滚。
UserTransaction 接口的主要方法包括：

void begin()：开始一个新的全局事务。
void commit()：提交当前活动的事务，使事务中的所有更改永久生效。
void rollback()：回滚当前活动的事务，撤销在该事务中进行的所有更改。
void setRollbackOnly()：标记当前事务只能被回滚，不允许提交。
int getStatus()：返回当前事务的状态，如是否处于活动状态、已提交、已回滚等。
通过这些方法，开发者能够在服务端应用程序中灵活地控制事务的生命周期，确保在多个资源之间保持数据的一致性和完整性。例如，在一个复杂的业务流程中，可能需要在一个事务上下文中执行多个操作，如果其中任何一个操作失败，则整个事务应当能够被回滚到最初的状态，这样就能维持事务的原子性和一致性原则