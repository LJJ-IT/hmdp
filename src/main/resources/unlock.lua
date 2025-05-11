-- 比较线程标识与锁中的标示是否一致
if redis.call("get", KEYS[1]) == ARGV[1] then
    -- 释放锁，删除锁
    return redis.call("del", KEYS[1])
end