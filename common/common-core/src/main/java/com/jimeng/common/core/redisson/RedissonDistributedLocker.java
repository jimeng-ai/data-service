package com.jimeng.common.core.redisson;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * @Author Moonlight
 * @Description redisson 分布所实现类
 * @Date 2024/7/13 19:49
 */

@Component
public class RedissonDistributedLocker implements DistributedLocker {

    @Autowired
    private RedissonClient redissonClient;

    @Override
    public void lock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock();
    }

    @Override
    public void lock(String lockKey, int leaseTime) {
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock(leaseTime, TimeUnit.SECONDS);
    }

    @Override
    public void lock(String lockKey, int timeout, TimeUnit unit) {
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock(timeout, unit);
    }

    @Override
    public boolean tryLock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean result = lock.tryLock();
        if (result){
            return true;
        }
        throw new ServiceException(ExceptionCode.SERVER_BUSY);
    }

    @Override
    public boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        RLock lock = redissonClient.getLock(lockKey);
        boolean result = lock.tryLock(waitTime, leaseTime, unit);
        if (result){
            return true;
        }
        throw new ServiceException(ExceptionCode.SERVER_BUSY);
    }

    @Override
    public void unlock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        lock.unlock();
    }

    @Override
    public void tryUnlock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        // 判断当前线程是否持有这个锁
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    @Override
    public boolean isLocked(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        return lock.isLocked();
    }

}
