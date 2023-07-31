
package jdk;
import java.util.concurrent.TimeUnit;
import java.util.Collection;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;


public class ReentrantReadWriteLock
        implements ReadWriteLock, java.io.Serializable {
    private static final long serialVersionUID = -6992448646407690164L;
    /** 读锁内部类对象 */
    private final ReentrantReadWriteLock.ReadLock readerLock;
    /** 写锁内部类对象 */
    private final ReentrantReadWriteLock.WriteLock writerLock;
    /** 几乎所有的同步功能都是sync实现的 */
    final Sync sync;


    public ReentrantReadWriteLock() {
        this(false);
    }

    public ReentrantReadWriteLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
        readerLock = new ReadLock(this);
        writerLock = new WriteLock(this);
    }

    public ReentrantReadWriteLock.WriteLock writeLock() { return writerLock; }
    public ReentrantReadWriteLock.ReadLock  readLock()  { return readerLock; }


    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 6317671515068378041L;


        /**
         * 读写锁所用到的一些常量，state高16位代表读锁，低16位代表写锁
         */
        static final int SHARED_SHIFT   = 16;
        static final int SHARED_UNIT    = (1 << SHARED_SHIFT);
        static final int MAX_COUNT      = (1 << SHARED_SHIFT) - 1;
        static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;

        /** 返回读锁的数量  */
        static int sharedCount(int c)    { return c >>> SHARED_SHIFT; }
        /** 返回写锁数量  */
        static int exclusiveCount(int c) { return c & EXCLUSIVE_MASK; }

        /**
         * 记录线程获取读锁的数量
         * 和ThreadLocalHoldCounter搭配使用
         */
        static final class HoldCounter {
            int count = 0;
            // Use id, not reference, to avoid garbage retention
            final long tid = getThreadId(Thread.currentThread());
        }

        /**
         * 记录每个线程获取读锁的数量
         */
        static final class ThreadLocalHoldCounter
            extends ThreadLocal<HoldCounter> {
            public HoldCounter initialValue() {
                return new HoldCounter();
            }
        }

        /**
         * 这是一个ThreadLocalHoldCounter类型的变量，它为每个线程存储一个HoldCounter对象。
         * HoldCounter对象用于记录每个线程获取读锁的次数。
         * 当一个线程的读锁计数降为0时，这个线程的HoldCounter对象就会被移除。
         */
        private transient ThreadLocalHoldCounter readHolds;

        /**
         * 这是一个HoldCounter类型的变量，它存储了最后一个成功获取读锁的线程的读锁计数。
         * 这个变量可以避免在最常见的情况下（下一个释放锁的线程就是最后一个获取锁的线程）进行ThreadLocal查找。
         * 这个变量是非volatile的，因为它只是用作启发式的，如果线程能够缓存它，那就太好了。
         */
        private transient HoldCounter cachedHoldCounter;

        /**
         * 这是一个Thread类型的变量，它记录了第一个获取读锁的线程。
         * 更准确地说，firstReader是最后一个将共享计数从0改为1的唯一线程，
         * 并且自那时以来还没有释放读锁；如果没有这样的线程，那么firstReader就是null。
         */
        private transient Thread firstReader = null;
        /**
         * 这是一个int类型的变量，它记录了firstReader的读锁计数。
         */
        private transient int firstReaderHoldCount;

        Sync() {
            readHolds = new ThreadLocalHoldCounter();
            setState(getState()); // ensures visibility of readHolds
        }

        /*
         * 获取和释放对于公平和非公平锁使用相同的代码，但是在队列非空时是否以及如何允许插队是不同的。
         */

        /**
         * 读锁是否应该阻塞
         */
        abstract boolean readerShouldBlock();

        /**
         * 写锁是否应该阻塞
         */
        abstract boolean writerShouldBlock();


        /**
         * 释放读锁逻辑
         */
        protected final boolean tryRelease(int releases) {
            //如果当前线程不持有锁，抛出异常
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            // 获取锁的状态并直接减去要释放的数量 ，这里可以直接减去的原因是因为低16位代表写锁
            int nextc = getState() - releases;
            //判断写锁是否完全释放
            boolean free = exclusiveCount(nextc) == 0;
            //如果完全释放，将独占锁置空
            if (free)
                setExclusiveOwnerThread(null);
            //没有完全释放，设置锁状态，然后返回读锁
            setState(nextc);
            return free;
        }
        //线程加写锁逻辑
        protected final boolean tryAcquire(int acquires) {
            //获取当前线程
            Thread current = Thread.currentThread();
            //获取state的值
            int c = getState();
            //获取写锁
            int w = exclusiveCount(c);
            //如果锁不为空
            if (c != 0) {
                //如果写锁为空（写锁为空意味着有线程持有读锁）或者当前持有锁的线程不是当前线程，争抢失败
                // (Note: if c != 0 and w == 0 then shared count != 0)
                if (w == 0 || current != getExclusiveOwnerThread())
                    return false;
                //如果写锁超过了65535，抛出异常
                if (w + exclusiveCount(acquires) > MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                // Reentrant acquire
                //处理重入，设置锁状态
                setState(c + acquires);
                return true;
            }
            //如果c==0，说明没有任何线程占有锁
            //判断当前线程是否应该被阻塞，如果应该被阻塞，返回false，如果不应该被阻塞 ，那么尝试获取锁，如果获取锁失败，返回false
            if (writerShouldBlock() ||
                !compareAndSetState(c, c + acquires))
                return false;
            //如果获取锁成功，设置独占锁，因为写锁是排他锁，一次只能有一个写锁
            setExclusiveOwnerThread(current);
            return true;
        }
        //释放共享锁（读锁）逻辑
        protected final boolean tryReleaseShared(int unused) {
            //获取当前线程
            Thread current = Thread.currentThread();
            //判断当前线程是否是第一个获取读锁的线程
            if (firstReader == current) {
                // 如果当前线程是第一个获取读锁的线程并且获取读锁的数量只有一个，
                //那么将第一个获取锁的线程置为空
                if (firstReaderHoldCount == 1)
                    firstReader = null;
                else
                //如果第一个线程获取的锁的数量不为1，减少锁数量
                    firstReaderHoldCount--;
            } else {
                //获取最后一个线程对应的HoldCounter
                HoldCounter rh = cachedHoldCounter;
                //如果rh为空或者不是当前线程对应的HoldCounter就从readHolds中获取
                if (rh == null || rh.tid != getThreadId(current))
                    rh = readHolds.get();
                //获取共享锁对应的数量
                int count = rh.count;
                //如果小于等于1，说明完全释放锁了，那就从readHolds中删除对应的HoldCounter
                if (count <= 1) {
                    readHolds.remove();
                    if (count <= 0)
                        throw unmatchedUnlockException();
                }
                //如果count>1 减少一就可以了
                --rh.count;
            }
            for (;;) {
                //获取读写锁的状态
                int c = getState();
                //读锁高位减一 ，SHARED_UNIT表示高位的一个读锁
                int nextc = c - SHARED_UNIT;
                //抢读锁
                if (compareAndSetState(c, nextc))
                    // Releasing the read lock has no effect on readers,
                    // but it may allow waiting writers to proceed if
                    // both read and write locks are now free.
                    //如果抢锁成功，返回是否所有的锁都被释放掉了，如果所有锁都释放掉了，返回true，否则返回false
                    return nextc == 0;
            }
        }
        //如果当前线程试图释放一个它并未持有的锁时抛出异常
        private IllegalMonitorStateException unmatchedUnlockException() {
            return new IllegalMonitorStateException(
                "attempt to unlock read lock, not locked by current thread");
        }
        //读锁加锁
        protected final int tryAcquireShared(int unused) {
            //获取当前线程
            Thread current = Thread.currentThread();
            //获取当前锁
            int c = getState();
            //如果有写锁未释放并且当前线程没有获取锁，返回-1
            if (exclusiveCount(c) != 0 &&
                getExclusiveOwnerThread() != current)
                return -1;
            //获取读锁
            int r = sharedCount(c);
            //如果当前线程不应该阻塞并且读锁并未超过最大限制就尝试获取锁
            if (!readerShouldBlock() &&
                r < MAX_COUNT &&
                compareAndSetState(c, c + SHARED_UNIT)) {
                //如果读锁为0，说明这个线程是第一个获取读锁的线程，那么把firstReader设置为当前线程，并且把firstReaderHoldCount设置为1
                if (r == 0) {
                    firstReader = current;
                    firstReaderHoldCount = 1;
                } else if (firstReader == current) {
                    //如果当前线程是第一个获取读锁的线程，firstReaderHoldCount加一
                    firstReaderHoldCount++;
                } else {
                    //上面的if逻辑都没进来，那么获取最后一个线程所代表的锁状态的HoldCounter
                    HoldCounter rh = cachedHoldCounter;
                    //如果rh不是当前线程所对应的rh，那么从readHolds中取出当前线程对应的HoldCounter
                    if (rh == null || rh.tid != getThreadId(current))
                        cachedHoldCounter = rh = readHolds.get();
                    else if (rh.count == 0)
                    //初始化当前线程对应的HoldCounter
                        readHolds.set(rh);
                    rh.count++;
                }
                return 1;
            }
            return fullTryAcquireShared(current);
        }

        /**
         * 这个方法用于为当前线程获取一个共享锁（读锁）。
         * 它是tryAcquireShared的更全面的版本，
         * 处理了CAS（Compare-and-Swap）操作失败和tryAcquireShared中未处理的重入读的情况。
         */
        final int fullTryAcquireShared(Thread current) {
            HoldCounter rh = null;
            for (;;) {
                //获取读写锁
                int c = getState();
                //如果有其他线程持有写锁
                if (exclusiveCount(c) != 0) {
                    //并且当前线程没有持有锁，那么返回-1
                    if (getExclusiveOwnerThread() != current)
                        return -1;
                    // else we hold the exclusive lock; blocking here
                    // would cause deadlock.
                    //如果当前线程持有读锁，阻塞在这，那么很有可能造成死锁
                } else if (readerShouldBlock()) {//判断当前线程是否应该阻塞
                    // Make sure we're not acquiring read lock reentrantly
                    //如果是第一次回去共享读锁的线程就什么都不做
                    if (firstReader == current) {
                        // assert firstReaderHoldCount > 0;
                    } else {
                        //如果不是第一个获取读锁的线程，从readHolds中回去读锁持有的数量
                        if (rh == null) {
                            rh = cachedHoldCounter;
                            if (rh == null || rh.tid != getThreadId(current)) {
                                rh = readHolds.get();
                                if (rh.count == 0)
                                    readHolds.remove();
                            }
                        }
                        if (rh.count == 0)
                            return -1;
                    }
                }
                //如果读锁已经达到了最大阈值，就抛出异常
                if (sharedCount(c) == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                //尝试获取读锁
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    //如果读锁为0，那么就是第一个获取读锁的线程
                    if (sharedCount(c) == 0) {
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        if (rh == null)
                            rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current))
                            rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                        cachedHoldCounter = rh; // cache for release
                    }
                    return 1;
                }
            }
        }

        /**
         * Performs tryLock for write, enabling barging in both modes.
         * This is identical in effect to tryAcquire except for lack
         * of calls to writerShouldBlock.
         */
        final boolean tryWriteLock() {
            Thread current = Thread.currentThread();
            int c = getState();
            if (c != 0) {
                int w = exclusiveCount(c);
                if (w == 0 || current != getExclusiveOwnerThread())
                    return false;
                if (w == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
            }
            if (!compareAndSetState(c, c + 1))
                return false;
            setExclusiveOwnerThread(current);
            return true;
        }

        /**
         * Performs tryLock for read, enabling barging in both modes.
         * This is identical in effect to tryAcquireShared except for
         * lack of calls to readerShouldBlock.
         */
        final boolean tryReadLock() {
            //获取当前线程
            Thread current = Thread.currentThread();
            for (;;) {
                //获取所有锁
                int c = getState();
                //如果写锁不为0并且不是当前线程持有锁
                if (exclusiveCount(c) != 0 &&
                    getExclusiveOwnerThread() != current)
                    return false;
                //获取读锁
                int r = sharedCount(c);
                //如果读锁达到了最大值
                if (r == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                //获取读锁
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    if (r == 0) {
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        HoldCounter rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current))
                            cachedHoldCounter = rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                    }
                    return true;
                }
            }
        }
        //判断当前是否独占锁
        protected final boolean isHeldExclusively() {
            // While we must in general read state before owner,
            // we don't need to do so to check if current thread is owner
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        // Methods relayed to outer class

        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        final Thread getOwner() {
            // Must read state before owner to ensure memory consistency
            return ((exclusiveCount(getState()) == 0) ?
                    null :
                    getExclusiveOwnerThread());
        }

        final int getReadLockCount() {
            return sharedCount(getState());
        }

        final boolean isWriteLocked() {
            return exclusiveCount(getState()) != 0;
        }

        final int getWriteHoldCount() {
            return isHeldExclusively() ? exclusiveCount(getState()) : 0;
        }

        final int getReadHoldCount() {
            if (getReadLockCount() == 0)
                return 0;

            Thread current = Thread.currentThread();
            if (firstReader == current)
                return firstReaderHoldCount;

            HoldCounter rh = cachedHoldCounter;
            if (rh != null && rh.tid == getThreadId(current))
                return rh.count;

            int count = readHolds.get().count;
            if (count == 0) readHolds.remove();
            return count;
        }

        /**
         * Reconstitutes the instance from a stream (that is, deserializes it).
         */
        private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            readHolds = new ThreadLocalHoldCounter();
            setState(0); // reset to unlocked state
        }

        final int getCount() { return getState(); }
    }

    /**
     * Nonfair version of Sync
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = -8159625535654395037L;
        final boolean writerShouldBlock() {
            return false; // writers can always barge
        }
        final boolean readerShouldBlock() {
            /* As a heuristic to avoid indefinite writer starvation,
             * block if the thread that momentarily appears to be head
             * of queue, if one exists, is a waiting writer.  This is
             * only a probabilistic effect since a new reader will not
             * block if there is a waiting writer behind other enabled
             * readers that have not yet drained from the queue.
             */
            //返回 队列中的第一个等待的线程是否正在等待获取独占锁
            return apparentlyFirstQueuedIsExclusive();
        }
    }

    /**
     * Fair version of Sync
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -2274990926593161451L;
        final boolean writerShouldBlock() {
            return hasQueuedPredecessors();
        }
        final boolean readerShouldBlock() {
            return hasQueuedPredecessors();
        }
    }

    /**
     * The lock returned by method {@link ReentrantReadWriteLock#readLock}.
     */
    public static class ReadLock implements Lock, java.io.Serializable {
        private static final long serialVersionUID = -5992448646407690164L;
        private final Sync sync;

        /**
         * Constructor for use by subclasses
         *
         * @param lock the outer lock object
         * @throws NullPointerException if the lock is null
         */
        protected ReadLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        /**
         * 获取读锁
         */
        public void lock() {
            sync.acquireShared(1);
        }

        /**
         * 获取锁（响应中断）
         */
        public void lockInterruptibly() throws InterruptedException {
            sync.acquireSharedInterruptibly(1);
        }

        /**
         * 尝试获取读锁，如果读锁立即可用，则获取读锁并立即返回true，如果读锁不可用，则立即返回false。
         */
        public boolean tryLock() {
            return sync.tryReadLock();
        }
        /**
         * 带超时时间的锁定
         */
        public boolean tryLock(long timeout, TimeUnit unit)
                throws InterruptedException {
            return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
        }

        /**
         * 解锁
         */
        public void unlock() {
            sync.releaseShared(1);
        }

        /**
         * Throws {@code UnsupportedOperationException} because
         * {@code ReadLocks} do not support conditions.
         *
         * @throws UnsupportedOperationException always
         */
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }

        /**
         * Returns a string identifying this lock, as well as its lock state.
         * The state, in brackets, includes the String {@code "Read locks ="}
         * followed by the number of held read locks.
         *
         * @return a string identifying this lock, as well as its lock state
         */
        public String toString() {
            int r = sync.getReadLockCount();
            return super.toString() +
                "[Read locks = " + r + "]";
        }
    }

    /**
     * 写锁
     */
    public static class WriteLock implements Lock, java.io.Serializable {
        private static final long serialVersionUID = -4992448646407690164L;
        private final Sync sync;

        /**
         * 写锁构造函数
         */
        protected WriteLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        /**
         * 写锁加锁
         */
        public void lock() {
            sync.acquire(1);
        }

       /**
        * 带相应中断的获取锁
        */
        public void lockInterruptibly() throws InterruptedException {
            sync.acquireInterruptibly(1);
        }

        /**
         * 尝试获取锁
         */
        public boolean tryLock( ) {
            return sync.tryWriteLock();
        }

        /**
         * 带超时时间的获取锁
         */
        public boolean tryLock(long timeout, TimeUnit unit)
                throws InterruptedException {
            return sync.tryAcquireNanos(1, unit.toNanos(timeout));
        }

        /**
         * 解锁
         */
        public void unlock() {
            sync.release(1);
        }

        
        public Condition newCondition() {
            return sync.newCondition();
        }

        /**
         * Returns a string identifying this lock, as well as its lock
         * state.  The state, in brackets includes either the String
         * {@code "Unlocked"} or the String {@code "Locked by"}
         * followed by the {@linkplain Thread#getName name} of the owning thread.
         *
         * @return a string identifying this lock, as well as its lock state
         */
        public String toString() {
            Thread o = sync.getOwner();
            return super.toString() + ((o == null) ?
                                       "[Unlocked]" :
                                       "[Locked by thread " + o.getName() + "]");
        }

        /**
         * 
         */
        public boolean isHeldByCurrentThread() {
            return sync.isHeldExclusively();
        }

        /**
         * 获取当前写锁持有的数量
         */
        public int getHoldCount() {
            return sync.getWriteHoldCount();
        }
    }

    /**
     * 判断是不是公平锁
     */
    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    /**
     * 获取当前持锁线程
     */
    protected Thread getOwner() {
        return sync.getOwner();
    }

    /**
     * 获取读锁持有的数量
     */
    public int getReadLockCount() {
        return sync.getReadLockCount();
    }

    /**
     * 判断有没有线程持有写锁
     */
    public boolean isWriteLocked() {
        return sync.isWriteLocked();
    }

    /**
     * 判断当前线程有没有持有写锁
     */
    public boolean isWriteLockedByCurrentThread() {
        return sync.isHeldExclusively();
    }

    /**
     * 获取写锁持有的数量
     */
    public int getWriteHoldCount() {
        return sync.getWriteHoldCount();
    }

    /**
     * 获取读锁持有的数量
     */
    public int getReadHoldCount() {
        return sync.getReadHoldCount();
    }

    /**
     * 获取当前正在等待获取写锁的线程集合
     */
    protected Collection<Thread> getQueuedWriterThreads() {
        return sync.getExclusiveQueuedThreads();
    }

    /**
     * 获取当前正在等待获取读锁的线程集合
     */
    protected Collection<Thread> getQueuedReaderThreads() {
        return sync.getSharedQueuedThreads();
    }

    /**
     * 查询是否有任何线程正在等待获取读锁或写锁
     */
    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    /**
     * 查询指定线程是否在排队
     */
    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }

    /**
     * 获取等待队列的长度
     */
    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    /**
     * 获取等待的线程集合
     */
    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }
    /**
     * 查询是否有线程正在等待与此锁相关的给定条件 Condition
     */
    public boolean hasWaiters(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * 返回等待与写锁关联的给定条件的线程数的估计值
     */
    public int getWaitQueueLength(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * 返回可能在与写锁关联的给定条件上等待的线程的集合
    */
    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * Returns a string identifying this lock, as well as its lock state.
     * The state, in brackets, includes the String {@code "Write locks ="}
     * followed by the number of reentrantly held write locks, and the
     * String {@code "Read locks ="} followed by the number of held
     * read locks.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
        int c = sync.getCount();
        int w = Sync.exclusiveCount(c);
        int r = Sync.sharedCount(c);

        return super.toString() +
            "[Write locks = " + w + ", Read locks = " + r + "]";
    }

    /**
     * 获取线程ID
     */
    static final long getThreadId(Thread thread) {
        return UNSAFE.getLongVolatile(thread, TID_OFFSET);
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long TID_OFFSET;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> tk = Thread.class;
            TID_OFFSET = UNSAFE.objectFieldOffset
                (tk.getDeclaredField("tid"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
