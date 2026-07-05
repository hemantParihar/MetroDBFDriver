// RowLockManager.java
package com.dbf.jdbc.lock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class RowLockManager {
    private final Map<Integer, ReentrantLock> locks = new ConcurrentHashMap<>();
    
    public boolean tryLock(int recordNumber, int timeoutMs) {
        ReentrantLock lock = locks.computeIfAbsent(recordNumber, k -> new ReentrantLock());
        try {
            return lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    public void unlock(int recordNumber) {
        ReentrantLock lock = locks.get(recordNumber);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                locks.remove(recordNumber);
            }
        }
    }
}