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


package org.quartz.impl.jdbcjobstore;

import java.lang.String;
import java.sql.Connection;
import java.sql.SQLException;

import org.quartz.JobPersistenceException;
import org.quartz.SchedulerConfigException;
import org.quartz.impl.jdbcjobstore.JobStoreSupport;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.SchedulerSignaler;
import org.quartz.utils.DBConnectionManager;

/**
 * <p>
 * <code>JobStoreCMT</code> is meant to be used in an application-server
 * environment that provides container-managed-transactions. No commit /
 * rollback will be1 handled by this class.
 * </p>
 * 
 * <p>
 * If you need commit / rollback, use <code>{@link
 * org.quartz.impl.jdbcjobstore.JobStoreTX}</code>
 * instead.
 * </p>
 * 
 * @author <a href="mailto:jeff@binaryfeed.org">Jeffrey Wescott</a>
 * @author James House
 * @author Srinivas Venkatarangaiah
 *  
 */
public class JobStoreCMT extends JobStoreSupport {

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Data members.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    protected String nonManagedTxDsName;

    // Great name huh?
    // 设置数据库事务是否为自动提交
    protected boolean dontSetNonManagedTXConnectionAutoCommitFalse = false;


    // 设置数据库事务隔离级别为ReadCommitted
    protected boolean setTxIsolationLevelReadCommitted = false;
    
    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Interface.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * Set the name of the <code>DataSource</code> that should be used for
     * performing database functions.
     * </p>
     */
    public void setNonManagedTXDataSource(String nonManagedTxDsName) {
        this.nonManagedTxDsName = nonManagedTxDsName;
    }

    /**
     * <p>
     * Get the name of the <code>DataSource</code> that should be used for
     * performing database functions.
     * </p>
     */
    public String getNonManagedTXDataSource() {
        return nonManagedTxDsName;
    }

    public boolean isDontSetNonManagedTXConnectionAutoCommitFalse() {
        return dontSetNonManagedTXConnectionAutoCommitFalse;
    }

    /**
     * Don't call set autocommit(false) on connections obtained from the
     * DataSource. This can be helpfull in a few situations, such as if you
     * have a driver that complains if it is called when it is already off.
     * 
     * @param b
     */
    public void setDontSetNonManagedTXConnectionAutoCommitFalse(boolean b) {
        dontSetNonManagedTXConnectionAutoCommitFalse = b;
    }


    public boolean isTxIsolationLevelReadCommitted() {
        return setTxIsolationLevelReadCommitted;
    }

    /**
     * Set the transaction isolation level of DB connections to sequential.
     * 
     * @param b
     */
    public void setTxIsolationLevelReadCommitted(boolean b) {
        setTxIsolationLevelReadCommitted = b;
    }
    

    @Override
    public void initialize(ClassLoadHelper loadHelper,
            SchedulerSignaler signaler) throws SchedulerConfigException {

        if (nonManagedTxDsName == null) {
            throw new SchedulerConfigException(
                "Non-ManagedTX DataSource name not set!  " +
                "If your 'org.quartz.jobStore.dataSource' is XA, then set " + 
                "'org.quartz.jobStore.nonManagedTXDataSource' to a non-XA "+ 
                "datasource (for the same DB).  " + 
                "Otherwise, you can set them to be the same.");
        }
        // 使用数据库锁
        if (getLockHandler() == null) {
            // If the user hasn't specified an explicit lock handler, 
            // then we *must* use DB locks with CMT...
            setUseDBLocks(true);
        }
        // 初始化父类
        super.initialize(loadHelper, signaler);

        getLog().info("JobStoreCMT initialized.");
    }
    
    @Override
    public void shutdown() {

        super.shutdown();
        
        try {
            DBConnectionManager.getInstance().shutdown(getNonManagedTXDataSource());
        } catch (SQLException sqle) {
            getLog().warn("Database connection shutdown unsuccessful.", sqle);
        }
    }

    @Override
    protected Connection getNonManagedTXConnection()
        throws JobPersistenceException {
        Connection conn = null;
        try {
            // 获取数据库连接
            conn = DBConnectionManager.getInstance().getConnection(
                    getNonManagedTXDataSource());
        } catch (SQLException sqle) {
            throw new JobPersistenceException(
                "Failed to obtain DB connection from data source '"
                        + getNonManagedTXDataSource() + "': "
                        + sqle.toString(), sqle);
        } catch (Throwable e) {
            throw new JobPersistenceException(
                "Failed to obtain DB connection from data source '"
                        + getNonManagedTXDataSource() + "': "
                        + e.toString(), e);
        }

        if (conn == null) { 
            throw new JobPersistenceException(
                "Could not get connection from DataSource '"
                        + getNonManagedTXDataSource() + "'"); 
        }

        // Protect connection attributes we might change.
        //TODO, 使用代理的方式， 保护我们可能更改的连接属性。
        conn = getAttributeRestoringConnection(conn);
        
        // Set any connection connection attributes we are to override.
        try {
            // 设置自动提交=false
            if (!isDontSetNonManagedTXConnectionAutoCommitFalse()) {
                conn.setAutoCommit(false);
            }
            // 设置事务隔离级别=ReadCommitted
            if (isTxIsolationLevelReadCommitted()) {
                conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            }
        } catch (SQLException sqle) {
            getLog().warn("Failed to override connection auto commit/transaction isolation.", sqle);
        } catch (Throwable e) {
            try { conn.close(); } catch(Throwable tt) {}
            
            throw new JobPersistenceException(
                "Failure setting up connection.", e);
        }
        
        return conn;
    }
    
    /**
     * Execute the given callback having optionally acquired the given lock.  
     * Because CMT assumes that the connection is already part of a managed
     * transaction, it does not attempt to commit or rollback the 
     * enclosing transaction.
     * 
     * @param lockName The name of the lock to acquire, for example 
     * "TRIGGER_ACCESS".  If null, then no lock is acquired, but the
     * txCallback is still executed in a transaction.
     * 
     * @see JobStoreSupport#executeInNonManagedTXLock(java.lang.String, org.quartz.impl.jdbcjobstore.JobStoreSupport.TransactionCallback, org.quartz.impl.jdbcjobstore.JobStoreSupport.TransactionValidator) 
     * @see JobStoreTX#executeInLock(java.lang.String, org.quartz.impl.jdbcjobstore.JobStoreSupport.TransactionCallback) 
     * @see JobStoreSupport#getNonManagedTXConnection()
     * @see JobStoreSupport#getConnection()
     */
    @Override
    protected Object executeInLock(
            String lockName, 
            TransactionCallback txCallback) throws JobPersistenceException {
        boolean transOwner = false;
        Connection conn = null;
        try {
            if (lockName != null) {
                // If we aren't using db locks, then delay getting DB connection 
                // until after acquiring the lock since it isn't needed.
                //如果我们不使用数据库锁，那么在获取锁之后再获取数据库连接，因为不需要这个锁。
                if (getLockHandler().requiresConnection()) {
                    conn = getConnection();
                }
                // 获取锁
                transOwner = getLockHandler().obtainLock(conn, lockName);
            }

            // 如果不需要锁，这一步不会执行,必须先获取锁
            if (conn == null) {
                conn = getConnection();
            }
            // 通过连接执行回调的接口语句
            return txCallback.execute(conn);
        } finally {
            try {
                releaseLock(lockName, transOwner);
            } finally {
                cleanupConnection(conn);
            }
        }
    }
}

// EOF
