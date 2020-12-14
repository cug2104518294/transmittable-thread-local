package com.alibaba.ttl;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link TransmittableThreadLocal}({@code TTL}) can transmit value from the thread of submitting task to the thread of executing task.
 * <p>
 * <b>Note</b>:<br>
 * {@link TransmittableThreadLocal} extends {@link InheritableThreadLocal},
 * so {@link TransmittableThreadLocal} first is a {@link InheritableThreadLocal}.<br>
 * If the <b>inheritable</b> ability from {@link InheritableThreadLocal} has <b>potential leaking problem</b>,
 * you can disable the <b>inheritable</b> ability:
 * <p>
 * ❶ For thread pooling components({@link java.util.concurrent.ThreadPoolExecutor},
 * {@link java.util.concurrent.ForkJoinPool}), Inheritable feature <b>should never</b> happen,
 * since threads in thread pooling components is pre-created and pooled, these threads is <b>neutral</b> to biz logic/data.
 * <br>
 * Disable inheritable for thread pooling components by wrapping thread factories using methods
 * {@link com.alibaba.ttl.threadpool.TtlExecutors#getDisableInheritableThreadFactory(java.util.concurrent.ThreadFactory) getDisableInheritableThreadFactory} /
 * {@link com.alibaba.ttl.threadpool.TtlForkJoinPoolHelper#getDefaultDisableInheritableForkJoinWorkerThreadFactory() getDefaultDisableInheritableForkJoinWorkerThreadFactory}.
 * <br>
 * Or you can turn on "disable inheritable for thread pool" by {@link com.alibaba.ttl.threadpool.agent.TtlAgent}
 * so as to wrap thread factories for thread pooling components automatically and transparently.
 * <p>
 * ❷ In other cases, disable inheritable by overriding method {@link #childValue(Object)}.
 * <br>
 * Whether the value should be inheritable or not can be controlled by the data owner,
 * disable it <b>carefully</b> when data owner have a clear idea.
 * <pre>{@code
 * TransmittableThreadLocal<String> transmittableThreadLocal = new TransmittableThreadLocal<>() {
 *     protected String childValue(String parentValue) {
 *         return initialValue();
 *     }
 * }}</pre>
 * <p>
 * More discussion about "disable the <b>inheritable</b> ability"
 * see <a href="https://github.com/alibaba/transmittable-thread-local/issues/100">
 * issue #100: disable Inheritable when it's not necessary and buggy</a>.
 *
 * @author Jerry Lee (oldratlee at gmail dot com)
 * @author Yang Fang (snoop dot fy at gmail dot com)
 * @see TtlRunnable
 * @see TtlCallable
 * @see com.alibaba.ttl.threadpool.TtlExecutors
 * @see com.alibaba.ttl.threadpool.TtlExecutors#getTtlExecutor(java.util.concurrent.Executor)
 * @see com.alibaba.ttl.threadpool.TtlExecutors#getTtlExecutorService(java.util.concurrent.ExecutorService)
 * @see com.alibaba.ttl.threadpool.TtlExecutors#getTtlScheduledExecutorService(java.util.concurrent.ScheduledExecutorService)
 * @see com.alibaba.ttl.threadpool.TtlExecutors#getDefaultDisableInheritableThreadFactory()
 * @see com.alibaba.ttl.threadpool.TtlExecutors#getDisableInheritableThreadFactory(java.util.concurrent.ThreadFactory)
 * @see com.alibaba.ttl.threadpool.TtlForkJoinPoolHelper
 * @see com.alibaba.ttl.threadpool.TtlForkJoinPoolHelper#getDefaultDisableInheritableForkJoinWorkerThreadFactory()
 * @see com.alibaba.ttl.threadpool.TtlForkJoinPoolHelper#getDisableInheritableForkJoinWorkerThreadFactory(java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory)
 * @see com.alibaba.ttl.threadpool.agent.TtlAgent
 * @since 0.10.0
 */
public class TransmittableThreadLocal<T> extends InheritableThreadLocal<T> implements TtlCopier<T> {
    // 日志句柄，使用的不是SLF4J的接口，而是java.util.logging的实现
    private static final Logger logger = Logger.getLogger(TransmittableThreadLocal.class.getName());
    // 是否禁用忽略NULL值的语义
    private final boolean disableIgnoreNullValueSemantics;

    /**
     * Default constructor. Create a {@link TransmittableThreadLocal} instance with "Ignore-Null-Value Semantics".
     * <p>
     * About "Ignore-Null-Value Semantics":
     * <p>
     * <ol>
     *     <li>If value is {@code null}(check by {@link #get()} method), do NOT transmit this {@code ThreadLocal}.</li>
     *     <li>If set {@code null} value, also remove value(invoke {@link #remove()} method).</li>
     * </ol>
     * <p>
     * This is a pragmatic design decision:
     * <ol>
     * <li>use explicit value type rather than {@code null} value to express biz intent.</li>
     * <li>safer and more robust code(avoid {@code NPE} risk).</li>
     * </ol>
     * <p>
     * So it's strongly not recommended to use {@code null} value.
     * <p>
     * But the behavior of "Ignore-Null-Value Semantics" is NOT compatible with
     * {@link ThreadLocal} and {@link InheritableThreadLocal},
     * you can disable this behavior/semantics via using constructor {@link #TransmittableThreadLocal(boolean)}
     * and setting parameter {@code disableIgnoreNullValueSemantics} to {@code true}.
     * <p>
     * More discussion about "Ignore-Null-Value Semantics" see
     * <a href="https://github.com/alibaba/transmittable-thread-local/issues/157">Issue #157</a>.
     *
     * @see #TransmittableThreadLocal(boolean)
     *
     * 默认是false，也就是不禁用忽略NULL值的语义，也就是忽略NULL值，也就是默认的话，NULL值传入不会覆盖原来已经存在的值
     */
    public TransmittableThreadLocal() {
        this(false);
    }

    /**
     * Constructor, create a {@link TransmittableThreadLocal} instance
     * with parameter {@code disableIgnoreNullValueSemantics} to control "Ignore-Null-Value Semantics".
     *
     * @param disableIgnoreNullValueSemantics disable "Ignore-Null-Value Semantics"
     * @see #TransmittableThreadLocal()
     * @since 2.11.3
     *
     * 可以通过手动设置，去覆盖IgnoreNullValue的语义，如果设置为true，则是支持NULL值的设置，设置为true的时候，与ThreadLocal的语义一致
     */
    public TransmittableThreadLocal(boolean disableIgnoreNullValueSemantics) {
        this.disableIgnoreNullValueSemantics = disableIgnoreNullValueSemantics;
    }

    /**
     * Computes the value for this transmittable thread-local variable
     * as a function of the source thread's value at the time the task
     * Object is created.
     * <p>
     * This method is called from {@link TtlRunnable} or
     * {@link TtlCallable} when it create, before the task is started.
     * <p>
     * This method merely returns reference of its source thread value(the shadow copy),
     * and should be overridden if a different behavior is desired.
     *
     * @since 1.0.0
     */
    @Override
    public T copy(T parentValue) {
        return parentValue;
    }

    /**
     * Callback method before task object({@link TtlRunnable}/{@link TtlCallable}) execute.
     * <p>
     * Default behavior is to do nothing, and should be overridden
     * if a different behavior is desired.
     * <p>
     * Do not throw any exception, just ignored.
     *
     * 模板方法，留给子类实现，在TtlRunnable或者TtlCallable执行前回调
     *
     * @since 1.2.0
     */
    protected void beforeExecute() {
    }

    /**
     * Callback method after task object({@link TtlRunnable}/{@link TtlCallable}) execute.
     * <p>
     * Default behavior is to do nothing, and should be overridden
     * if a different behavior is desired.
     * <p>
     * Do not throw any exception, just ignored.
     *
     * 模板方法，留给子类实现，在TtlRunnable或者TtlCallable执行后回调
     * @since 1.2.0
     */
    protected void afterExecute() {
    }

    /**
     * see {@link InheritableThreadLocal#get()}
     *
     * 获取值，直接从InheritableThreadLocal#get()获取
     */
    @Override
    public final T get() {
        T value = super.get();
        // 如果值不为NULL 或者 禁用了忽略空值的语义（也就是和ThreadLocal语义一致），则重新添加TTL实例自身到存储器
        if (disableIgnoreNullValueSemantics || null != value) {
            addThisToHolder();
        }
        return value;
    }

    /**
     * see {@link InheritableThreadLocal#set}
     */
    @Override
    public final void set(T value) {
        // 如果不禁用忽略空值的语义，也就是需要忽略空值，并且设置的入参值为空，则做一次彻底的移除，包括从存储器移除TTL自身实例，TTL（ThrealLocalMap）中也移除对应的值
        if (!disableIgnoreNullValueSemantics && null == value) {
            // may set null to remove value
            remove();
        } else {
            // TTL（ThrealLocalMap）中设置对应的值
            super.set(value);
            // 添加TTL实例自身到存储器
            addThisToHolder();
        }
    }

    /**
     * see {@link InheritableThreadLocal#remove()}
     *
     * 从存储器移除TTL自身实例，从TTL（ThrealLocalMap）中移除对应的值
     */
    @Override
    public final void remove() {
        removeThisFromHolder();
        super.remove();
    }

    // 从TTL（ThrealLocalMap）中移除对应的值
    private void superRemove() {
        super.remove();
    }

    // 拷贝值，主要是拷贝get()的返回值
    private T copyValue() {
        return copy(get());
    }


    // 存储器，本身就是一个InheritableThreadLocal（ThreadLocal）
    // 它的存放对象是WeakHashMap<TransmittableThreadLocal<Object>, ?>类型，而WeakHashMap的VALUE总是为NULL，这里当做Set容器使用，WeakHashMap支持NULL值
    // holder是全局静态的，并且它自身也是一个InheritableThreadLocal（get()方法也是线程隔离的），它实际上就是父线程管理所有TransmittableThreadLocal的桥梁

    // Note about the holder:
    // 1. holder self is a InheritableThreadLocal(a *ThreadLocal*).
    // 2. The type of value in the holder is WeakHashMap<TransmittableThreadLocal<Object>, ?>.
    //    2.1 but the WeakHashMap is used as a *Set*:
    //        the value of WeakHashMap is *always* null, and never used.
    //    2.2 WeakHashMap support *null* value.

    private static final InheritableThreadLocal<WeakHashMap<TransmittableThreadLocal<Object>, ?>> holder =
            new InheritableThreadLocal<WeakHashMap<TransmittableThreadLocal<Object>, ?>>() {
                @Override
                protected WeakHashMap<TransmittableThreadLocal<Object>, ?> initialValue() {
                    return new WeakHashMap<TransmittableThreadLocal<Object>, Object>();
                }

                @Override
                protected WeakHashMap<TransmittableThreadLocal<Object>, ?> childValue(WeakHashMap<TransmittableThreadLocal<Object>, ?> parentValue) {
                    return new WeakHashMap<TransmittableThreadLocal<Object>, Object>(parentValue);
                }
            };

    //添加TTL自身实例到存储器，不存在则添加策略
    @SuppressWarnings("unchecked")
    private void addThisToHolder() {
        if (!holder.get().containsKey(this)) {
            // WeakHashMap supports null value.
            holder.get().put((TransmittableThreadLocal<Object>) this, null);
        }
    }

    //从存储器移除TTL自身的实例
    private void removeThisFromHolder() {
        holder.get().remove(this);
    }

    //执行目标方法，isBefore决定回调beforeExecute还是afterExecute，注意此回调方法会吞掉所有的异常只打印日志
    private static void doExecuteCallback(boolean isBefore) {
        for (TransmittableThreadLocal<Object> threadLocal : holder.get().keySet()) {
            try {
                if (isBefore) {
                    threadLocal.beforeExecute();
                } else {
                    threadLocal.afterExecute();
                }
            } catch (Throwable t) {
                if (logger.isLoggable(Level.WARNING)) {
                    logger.log(Level.WARNING, "TTL exception when " + (isBefore ? "beforeExecute" : "afterExecute") + ", cause: " + t.toString(), t);
                }
            }
        }
    }

    /**
     * Debug only method!
     */
    static void dump(@Nullable String title) {
        if (title != null && title.length() > 0) {
            System.out.printf("Start TransmittableThreadLocal[%s] Dump...%n", title);
        } else {
            System.out.println("Start TransmittableThreadLocal Dump...");
        }

        for (TransmittableThreadLocal<Object> threadLocal : holder.get().keySet()) {
            System.out.println(threadLocal.get());
        }
        System.out.println("TransmittableThreadLocal Dump end!");
    }

    /**
     * Debug only method!
     *
     * DEBUG模式下打印TTL里面的所有值
     */
    static void dump() {
        dump(null);
    }

    /**
     * {@link Transmitter} transmit all {@link TransmittableThreadLocal}
     * and registered {@link ThreadLocal}(registered by {@link Transmitter#registerThreadLocal})
     * values of the current thread to other thread by static methods
     * {@link #capture()} =&gt; {@link #replay(Object)} =&gt; {@link #restore(Object)} (aka {@code CRR} operation).
     * <p>
     * {@link Transmitter} is <b><i>internal</i></b> manipulation api for <b><i>framework/middleware integration</i></b>;
     * In general, you will <b><i>never</i></b> use it in the <i>biz/application code</i>!
     *
     * <h2>Framework/Middleware integration to TTL transmittance</h2>
     * Below is the example code:
     *
     * <pre>{@code
     * ///////////////////////////////////////////////////////////////////////////
     * // in thread A, capture all TransmittableThreadLocal values of thread A
     * ///////////////////////////////////////////////////////////////////////////
     *
     * Object captured = Transmitter.capture(); // (1)
     *
     * ///////////////////////////////////////////////////////////////////////////
     * // in thread B
     * ///////////////////////////////////////////////////////////////////////////
     *
     * // replay all TransmittableThreadLocal values from thread A
     * Object backup = Transmitter.replay(captured); // (2)
     * try {
     *     // your biz logic, run with the TransmittableThreadLocal values of thread B
     *     System.out.println("Hello");
     *     // ...
     *     return "World";
     * } finally {
     *     // restore the TransmittableThreadLocal of thread B when replay
     *     Transmitter.restore(backup); (3)
     * }}</pre>
     * <p>
     * see the implementation code of {@link TtlRunnable} and {@link TtlCallable} for more actual code sample.
     * <p>
     * Of course, {@link #replay(Object)} and {@link #restore(Object)} operation can be simplified by util methods
     * {@link #runCallableWithCaptured(Object, Callable)} or {@link #runSupplierWithCaptured(Object, Supplier)}
     * and the adorable {@code Java 8 lambda syntax}.
     * <p>
     * Below is the example code:
     *
     * <pre>{@code
     * ///////////////////////////////////////////////////////////////////////////
     * // in thread A, capture all TransmittableThreadLocal values of thread A
     * ///////////////////////////////////////////////////////////////////////////
     *
     * Object captured = Transmitter.capture(); // (1)
     *
     * ///////////////////////////////////////////////////////////////////////////
     * // in thread B
     * ///////////////////////////////////////////////////////////////////////////
     *
     * String result = runSupplierWithCaptured(captured, () -> {
     *      // your biz logic, run with the TransmittableThreadLocal values of thread A
     *      System.out.println("Hello");
     *      ...
     *      return "World";
     * }); // (2) + (3)}</pre>
     * <p>
     * The reason of providing 2 util methods is the different {@code throws Exception} type
     * so as to satisfy your biz logic({@code lambda}):
     * <ol>
     * <li>{@link #runCallableWithCaptured(Object, Callable)}: {@code throws Exception}</li>
     * <li>{@link #runSupplierWithCaptured(Object, Supplier)}: No {@code throws}</li>
     * </ol>
     * <p>
     * If you need the different {@code throws Exception} type,
     * you can define your own util method(function interface({@code lambda}))
     * with your own {@code throws Exception} type.
     *
     * <h2>ThreadLocal Integration</h2>
     * If you can not rewrite the existed code which use {@link ThreadLocal} to {@link TransmittableThreadLocal},
     * register the {@link ThreadLocal} instances via the methods
     * {@link #registerThreadLocal(ThreadLocal, TtlCopier)}/{@link #registerThreadLocalWithShadowCopier(ThreadLocal)}
     * to enhance the <b>Transmittable</b> ability for the existed {@link ThreadLocal} instances.
     * <p>
     * Below is the example code:
     *
     * <pre>{@code
     * // the value of this ThreadLocal instance will be transmitted after registered
     * Transmitter.registerThreadLocal(aThreadLocal, copyLambda);
     *
     * // Then the value of this ThreadLocal instance will not be transmitted after unregistered
     * Transmitter.unregisterThreadLocal(aThreadLocal);}</pre>
     *
     * <B><I>Caution:</I></B><br>
     * If the registered {@link ThreadLocal} instance is not {@link InheritableThreadLocal},
     * the instance can NOT <B><I>{@code inherit}</I></B> value from parent thread(aka. the <b>inheritable</b> ability)!
     *
     * @author Yang Fang (snoop dot fy at gmail dot com)
     * @author Jerry Lee (oldratlee at gmail dot com)
     * @see TtlRunnable
     * @see TtlCallable
     * @since 2.3.0
     */
    public static class Transmitter {

        //######################################### 捕获 ###########################################################
        /**
         * Capture all {@link TransmittableThreadLocal} and registered {@link ThreadLocal} values in the current thread.
         *
         * @return the captured {@link TransmittableThreadLocal} values
         * @since 2.3.0
         *
         * 捕获当前线程绑定的所有的TransmittableThreadLocal和已经注册的ThreadLocal的值 - 使用了用时拷贝快照的策略
         * 笔者注：它一般在构造任务实例的时候被调用，因此当前线程相对于子线程或者线程池的任务就是父线程，其实本质是捕获父线程的所有线程本地变量的值
         *
         */
        @NonNull
        public static Object capture() {
            return new Snapshot(captureTtlValues(), captureThreadLocalValues());
        }

        // 新建一个WeakHashMap，遍历TransmittableThreadLocal#holder中的所有TransmittableThreadLocal的Entry，获取K-V，存放到这个新的WeakHashMap返回
        private static HashMap<TransmittableThreadLocal<Object>, Object> captureTtlValues() {
            HashMap<TransmittableThreadLocal<Object>, Object> ttl2Value = new HashMap<TransmittableThreadLocal<Object>, Object>();
            for (TransmittableThreadLocal<Object> threadLocal : holder.get().keySet()) {
                ttl2Value.put(threadLocal, threadLocal.copyValue());
            }
            return ttl2Value;
        }
        // 新建一个WeakHashMap，遍历threadLocalHolder中的所有ThreadLocal的Entry，获取K-V，存放到这个新的WeakHashMap返回
        private static HashMap<ThreadLocal<Object>, Object> captureThreadLocalValues() {
            final HashMap<ThreadLocal<Object>, Object> threadLocal2Value = new HashMap<ThreadLocal<Object>, Object>();
            for (Map.Entry<ThreadLocal<Object>, TtlCopier<Object>> entry : threadLocalHolder.entrySet()) {
                final ThreadLocal<Object> threadLocal = entry.getKey();
                final TtlCopier<Object> copier = entry.getValue();
                threadLocal2Value.put(threadLocal, copier.copy(threadLocal.get()));
            }
            return threadLocal2Value;
        }

        //######################################### 重放 ###########################################################
        /**
         * Replay the captured {@link TransmittableThreadLocal} and registered {@link ThreadLocal} values from {@link #capture()},
         * and return the backup {@link TransmittableThreadLocal} values in the current thread before replay.
         *
         * @param captured captured {@link TransmittableThreadLocal} values from other thread from {@link #capture()}
         * @return the backup {@link TransmittableThreadLocal} values before replay
         * @see #capture()
         * @since 2.3.0
         */
        // 重放capture()方法中捕获的TransmittableThreadLocal和手动注册的ThreadLocal中的值，本质是重新拷贝holder中的所有变量，生成新的快照
        // 笔者注：重放操作一般会在子线程或者线程池中的线程的任务执行的时候调用，
        // 因此此时的holder#get()拿到的是子线程的原来就存在的本地线程变量，重放操作就是把这些子线程原有的本地线程变量备份
        @NonNull
        public static Object replay(@NonNull Object captured) {
            final Snapshot capturedSnapshot = (Snapshot) captured;
            return new Snapshot(replayTtlValues(capturedSnapshot.ttl2Value), replayThreadLocalValues(capturedSnapshot.threadLocal2Value));
        }

        @NonNull
        private static HashMap<TransmittableThreadLocal<Object>, Object> replayTtlValues(@NonNull HashMap<TransmittableThreadLocal<Object>, Object> captured) {
            // 新建一个新的备份WeakHashMap，其实也是一个快照
            HashMap<TransmittableThreadLocal<Object>, Object> backup = new HashMap<TransmittableThreadLocal<Object>, Object>();
            // 这里的循环针对的是子线程，用于获取的是子线程的所有线程本地变量
            for (final Iterator<TransmittableThreadLocal<Object>> iterator = holder.get().keySet().iterator(); iterator.hasNext(); ) {
                TransmittableThreadLocal<Object> threadLocal = iterator.next();
                // backup
                // 拷贝holder当前线程（子线程）绑定的所有TransmittableThreadLocal的K-V结构到备份中
                backup.put(threadLocal, threadLocal.get());
                // clear the TTL values that is not in captured
                // avoid the extra TTL values after replay when run task
                // 清理所有的非捕获快照中的TTL变量，以防有中间过程引入的额外的TTL变量（除了父线程的本地变量）影响了任务执行后的重放操作
                // 简单来说就是：移除所有子线程的不包含在父线程捕获的线程本地变量集合的中所有子线程本地变量和对应的值
                /**
                 * 这个问题可以举个简单的例子：
                 * static TransmittableThreadLocal<Integer> TTL = new TransmittableThreadLocal<>();
                 * 线程池中的子线程C中原来初始化的时候，在线程C中绑定了TTL的值为10087，C线程是核心线程不会主动销毁。
                 * 父线程P在没有设置TTL值的前提下，调用了线程C去执行任务，那么在C线程的Runnable包装类中通过TTL#get()就会获取到10087，显然是不符合预期的
                 * 所以，在C线程的Runnable包装类之前之前，要从C线程的线程本地变量，移除掉不包含在父线程P中的所有线程本地变量，
                 * 确保Runnable包装类执行期间只能拿到父线程中捕获到的线程本地变量
                 * 下面这个判断和移除做的就是这个工作
                 */
                if (!captured.containsKey(threadLocal)) {
                    iterator.remove();
                    threadLocal.superRemove();
                }
            }
            // set TTL values to captured
            // 重新设置TTL的值到捕获的快照中
            // 其实真实的意图是:把从父线程中捕获的所有线程本地变量重写设置到TTL中，本质上，子线程holder里面的TTL绑定的值会被刷新
            setTtlValuesTo(captured);
            // call beforeExecute callback
            // 回调模板方法beforeExecute
            doExecuteCallback(true);
            return backup;
        }

        // 重放所有的手动注册的ThreadLocal的值
        private static HashMap<ThreadLocal<Object>, Object> replayThreadLocalValues(@NonNull HashMap<ThreadLocal<Object>, Object> captured) {
            final HashMap<ThreadLocal<Object>, Object> backup = new HashMap<ThreadLocal<Object>, Object>();
            for (Map.Entry<ThreadLocal<Object>, Object> entry : captured.entrySet()) {
                final ThreadLocal<Object> threadLocal = entry.getKey();
                backup.put(threadLocal, threadLocal.get());
                final Object value = entry.getValue();
                //如果值为清除标记则绑定在当前线程的变量进行remove，否则设置值覆盖
                if (value == threadLocalClearMark) {
                    threadLocal.remove();
                } else {
                    threadLocal.set(value);
                }
            }
            return backup;
        }

        /**
         * Clear all {@link TransmittableThreadLocal} and registered {@link ThreadLocal} values in the current thread,
         * and return the backup {@link TransmittableThreadLocal} values in the current thread before clear.
         *
         * @return the backup {@link TransmittableThreadLocal} values before clear
         * @since 2.9.0
         */
        @NonNull
        public static Object clear() {
            final HashMap<TransmittableThreadLocal<Object>, Object> ttl2Value = new HashMap<TransmittableThreadLocal<Object>, Object>();

            final HashMap<ThreadLocal<Object>, Object> threadLocal2Value = new HashMap<ThreadLocal<Object>, Object>();
            for (Map.Entry<ThreadLocal<Object>, TtlCopier<Object>> entry : threadLocalHolder.entrySet()) {
                final ThreadLocal<Object> threadLocal = entry.getKey();
                threadLocal2Value.put(threadLocal, threadLocalClearMark);
            }

            return replay(new Snapshot(ttl2Value, threadLocal2Value));
        }

        /**
         * Restore the backup {@link TransmittableThreadLocal} and
         * registered {@link ThreadLocal} values from {@link #replay(Object)}/{@link #clear()}.
         *
         * @param backup the backup {@link TransmittableThreadLocal} values from {@link #replay(Object)}/{@link #clear()}
         * @see #replay(Object)
         * @see #clear()
         * @since 2.3.0
         *
         * 从relay()或者clear()方法中恢复TransmittableThreadLocal和手工注册的ThreadLocal的值对应的备份
         * 笔者注：恢复操作一般会在子线程或者线程池中的线程的任务执行的时候调用
         */
        public static void restore(@NonNull Object backup) {
            final Snapshot backupSnapshot = (Snapshot) backup;
            restoreTtlValues(backupSnapshot.ttl2Value);
            restoreThreadLocalValues(backupSnapshot.threadLocal2Value);
        }

        private static void restoreTtlValues(@NonNull HashMap<TransmittableThreadLocal<Object>, Object> backup) {
            // call afterExecute callback
            doExecuteCallback(false);
            for (final Iterator<TransmittableThreadLocal<Object>> iterator = holder.get().keySet().iterator(); iterator.hasNext(); ) {
                TransmittableThreadLocal<Object> threadLocal = iterator.next();
                // clear the TTL values that is not in backup
                // avoid the extra TTL values after restore
                if (!backup.containsKey(threadLocal)) {
                    iterator.remove();
                    threadLocal.superRemove();
                }
            }
            // restore TTL values
            setTtlValuesTo(backup);
        }

        // 提取WeakHashMap中的KeySet，遍历所有的TransmittableThreadLocal，重新设置VALUE
        private static void setTtlValuesTo(@NonNull HashMap<TransmittableThreadLocal<Object>, Object> ttlValues) {
            for (Map.Entry<TransmittableThreadLocal<Object>, Object> entry : ttlValues.entrySet()) {
                TransmittableThreadLocal<Object> threadLocal = entry.getKey();
                // 重新设置TTL值，本质上，当前线程（子线程）holder里面的TTL绑定的值会被刷新
                threadLocal.set(entry.getValue());
            }
        }

        private static void restoreThreadLocalValues(@NonNull HashMap<ThreadLocal<Object>, Object> backup) {
            for (Map.Entry<ThreadLocal<Object>, Object> entry : backup.entrySet()) {
                final ThreadLocal<Object> threadLocal = entry.getKey();
                threadLocal.set(entry.getValue());
            }
        }

        // 私有静态类，快照，保存从holder中捕获的所有TransmittableThreadLocal和外部手动注册保存在threadLocalHolder的ThreadLocal的K-V映射快照
        private static class Snapshot {
            final HashMap<TransmittableThreadLocal<Object>, Object> ttl2Value;
            final HashMap<ThreadLocal<Object>, Object> threadLocal2Value;
            private Snapshot(HashMap<TransmittableThreadLocal<Object>, Object> ttl2Value, HashMap<ThreadLocal<Object>, Object> threadLocal2Value) {
                this.ttl2Value = ttl2Value;
                this.threadLocal2Value = threadLocal2Value;
            }
        }

        /**
         * Util method for simplifying {@link #replay(Object)} and {@link #restore(Object)} operation.
         *
         * @param captured captured {@link TransmittableThreadLocal} values from other thread from {@link #capture()}
         * @param bizLogic biz logic
         * @param <R>      the return type of biz logic
         * @return the return value of biz logic
         * @see #capture()
         * @see #replay(Object)
         * @see #restore(Object)
         * @since 2.3.1
         */
        public static <R> R runSupplierWithCaptured(@NonNull Object captured, @NonNull Supplier<R> bizLogic) {
            final Object backup = replay(captured);
            try {
                return bizLogic.get();
            } finally {
                restore(backup);
            }
        }

        /**
         * Util method for simplifying {@link #clear()} and {@link #restore(Object)} operation.
         *
         * @param bizLogic biz logic
         * @param <R>      the return type of biz logic
         * @return the return value of biz logic
         * @see #clear()
         * @see #restore(Object)
         * @since 2.9.0
         */
        public static <R> R runSupplierWithClear(@NonNull Supplier<R> bizLogic) {
            final Object backup = clear();
            try {
                return bizLogic.get();
            } finally {
                restore(backup);
            }
        }

        /**
         * Util method for simplifying {@link #replay(Object)} and {@link #restore(Object)} operation.
         *
         * @param captured captured {@link TransmittableThreadLocal} values from other thread from {@link #capture()}
         * @param bizLogic biz logic
         * @param <R>      the return type of biz logic
         * @return the return value of biz logic
         * @throws Exception exception threw by biz logic
         * @see #capture()
         * @see #replay(Object)
         * @see #restore(Object)
         * @since 2.3.1
         */
        public static <R> R runCallableWithCaptured(@NonNull Object captured, @NonNull Callable<R> bizLogic) throws Exception {
            final Object backup = replay(captured);
            try {
                return bizLogic.call();
            } finally {
                restore(backup);
            }
        }

        /**
         * Util method for simplifying {@link #clear()} and {@link #restore(Object)} operation.
         *
         * @param bizLogic biz logic
         * @param <R>      the return type of biz logic
         * @return the return value of biz logic
         * @throws Exception exception threw by biz logic
         * @see #clear()
         * @see #restore(Object)
         * @since 2.9.0
         */
        public static <R> R runCallableWithClear(@NonNull Callable<R> bizLogic) throws Exception {
            final Object backup = clear();
            try {
                return bizLogic.call();
            } finally {
                restore(backup);
            }
        }

        // 保存手动注册的ThreadLocal->TtlCopier映射，这里是因为部分API提供了TtlCopier给用户实现
        private static volatile WeakHashMap<ThreadLocal<Object>, TtlCopier<Object>> threadLocalHolder = new WeakHashMap<ThreadLocal<Object>, TtlCopier<Object>>();
        // threadLocalHolder更变时候的监视器
        private static final Object threadLocalHolderUpdateLock = new Object();
        // 标记WeakHashMap中的ThreadLocal的对应值为NULL的属性，便于后面清理
        private static final Object threadLocalClearMark = new Object();

        /**
         * Register the {@link ThreadLocal}(including subclass {@link InheritableThreadLocal}) instances
         * to enhance the <b>Transmittable</b> ability for the existed {@link ThreadLocal} instances.
         * <p>
         * If the registered {@link ThreadLocal} instance is {@link TransmittableThreadLocal} just ignores and return {@code true}.
         * since a {@link TransmittableThreadLocal} instance itself has the {@code Transmittable} ability,
         * it is unnecessary to register a {@link TransmittableThreadLocal} instance.
         *
         * @param threadLocal the {@link ThreadLocal} instance that to enhance the <b>Transmittable</b> ability
         * @param copier      the {@link TtlCopier}
         * @return {@code true} if register the {@link ThreadLocal} instance and set {@code copier}, otherwise {@code false}
         * @see #registerThreadLocal(ThreadLocal, TtlCopier, boolean)
         * @since 2.11.0
         *
         * 默认的拷贝器，影子拷贝，直接返回父值
         */
        public static <T> boolean registerThreadLocal(@NonNull ThreadLocal<T> threadLocal, @NonNull TtlCopier<T> copier) {
            return registerThreadLocal(threadLocal, copier, false);
        }

        /**
         * Register the {@link ThreadLocal}(including subclass {@link InheritableThreadLocal}) instances
         * to enhance the <b>Transmittable</b> ability for the existed {@link ThreadLocal} instances.
         * <p>
         * Use the shadow copier(transmit the reference directly),
         * and should use {@link #registerThreadLocal(ThreadLocal, TtlCopier)} to pass a {@link TtlCopier} explicitly
         * if a different behavior is desired.
         * <p>
         * If the registered {@link ThreadLocal} instance is {@link TransmittableThreadLocal} just ignores and return {@code true}.
         * since a {@link TransmittableThreadLocal} instance itself has the {@code Transmittable} ability,
         * it is unnecessary to register a {@link TransmittableThreadLocal} instance.
         *
         * @param threadLocal the {@link ThreadLocal} instance that to enhance the <b>Transmittable</b> ability
         * @return {@code true} if register the {@link ThreadLocal} instance and set {@code copier}, otherwise {@code false}
         * @see #registerThreadLocal(ThreadLocal, TtlCopier)
         * @see #registerThreadLocal(ThreadLocal, TtlCopier, boolean)
         * @since 2.11.0
         */
        @SuppressWarnings("unchecked")
        public static <T> boolean registerThreadLocalWithShadowCopier(@NonNull ThreadLocal<T> threadLocal) {
            return registerThreadLocal(threadLocal, (TtlCopier<T>) shadowCopier, false);
        }

        /**
         * Register the {@link ThreadLocal}(including subclass {@link InheritableThreadLocal}) instances
         * to enhance the <b>Transmittable</b> ability for the existed {@link ThreadLocal} instances.
         * <p>
         * If the registered {@link ThreadLocal} instance is {@link TransmittableThreadLocal} just ignores and return {@code true}.
         * since a {@link TransmittableThreadLocal} instance itself has the {@code Transmittable} ability,
         * it is unnecessary to register a {@link TransmittableThreadLocal} instance.
         *
         * @param threadLocal the {@link ThreadLocal} instance that to enhance the <b>Transmittable</b> ability
         * @param copier      the {@link TtlCopier}
         * @param force       if {@code true}, update {@code copier} to {@link ThreadLocal} instance
         *                    when a {@link ThreadLocal} instance is already registered; otherwise, ignore.
         * @return {@code true} if register the {@link ThreadLocal} instance and set {@code copier}, otherwise {@code false}
         * @see #registerThreadLocal(ThreadLocal, TtlCopier)
         * @since 2.11.0
         */
        @SuppressWarnings("unchecked")
        public static <T> boolean registerThreadLocal(@NonNull ThreadLocal<T> threadLocal, @NonNull TtlCopier<T> copier, boolean force) {

            if (threadLocal instanceof TransmittableThreadLocal) {
                logger.warning("register a TransmittableThreadLocal instance, this is unnecessary!");
                return true;
            }

            synchronized (threadLocalHolderUpdateLock) {
                if (!force && threadLocalHolder.containsKey(threadLocal)) {
                    return false;
                }
                WeakHashMap<ThreadLocal<Object>, TtlCopier<Object>> newHolder = new WeakHashMap<ThreadLocal<Object>, TtlCopier<Object>>(threadLocalHolder);
                newHolder.put((ThreadLocal<Object>) threadLocal, (TtlCopier<Object>) copier);
                threadLocalHolder = newHolder;
                return true;
            }
        }

        /**
         * Register the {@link ThreadLocal}(including subclass {@link InheritableThreadLocal}) instances
         * to enhance the <b>Transmittable</b> ability for the existed {@link ThreadLocal} instances.
         * <p>
         * Use the shadow copier(transmit the reference directly),
         * and should use {@link #registerThreadLocal(ThreadLocal, TtlCopier, boolean)} to pass a {@link TtlCopier} explicitly
         * if a different behavior is desired.
         * <p>
         * If the registered {@link ThreadLocal} instance is {@link TransmittableThreadLocal} just ignores and return {@code true}.
         * since a {@link TransmittableThreadLocal} instance itself has the {@code Transmittable} ability,
         * it is unnecessary to register a {@link TransmittableThreadLocal} instance.
         *
         * @param threadLocal the {@link ThreadLocal} instance that to enhance the <b>Transmittable</b> ability
         * @param force       if {@code true}, update {@code copier} to {@link ThreadLocal} instance
         *                    when a {@link ThreadLocal} instance is already registered; otherwise, ignore.
         * @return {@code true} if register the {@link ThreadLocal} instance and set {@code copier}, otherwise {@code false}
         * @see #registerThreadLocal(ThreadLocal, TtlCopier)
         * @see #registerThreadLocal(ThreadLocal, TtlCopier, boolean)
         * @since 2.11.0
         */
        @SuppressWarnings("unchecked")
        public static <T> boolean registerThreadLocalWithShadowCopier(@NonNull ThreadLocal<T> threadLocal, boolean force) {
            return registerThreadLocal(threadLocal, (TtlCopier<T>) shadowCopier, force);
        }

        /**
         * Unregister the {@link ThreadLocal} instances
         * to remove the <b>Transmittable</b> ability for the {@link ThreadLocal} instances.
         * <p>
         * If the {@link ThreadLocal} instance is {@link TransmittableThreadLocal} just ignores and return {@code true}.
         *
         * @see #registerThreadLocal(ThreadLocal, TtlCopier)
         * @see #registerThreadLocalWithShadowCopier(ThreadLocal)
         * @since 2.11.0
         */
        public static <T> boolean unregisterThreadLocal(@NonNull ThreadLocal<T> threadLocal) {
            if (threadLocal instanceof TransmittableThreadLocal) {
                logger.warning("unregister a TransmittableThreadLocal instance, this is unnecessary!");
                return true;
            }

            synchronized (threadLocalHolderUpdateLock) {
                if (!threadLocalHolder.containsKey(threadLocal)) {
                    return false;
                }

                WeakHashMap<ThreadLocal<Object>, TtlCopier<Object>> newHolder = new WeakHashMap<ThreadLocal<Object>, TtlCopier<Object>>(threadLocalHolder);
                newHolder.remove(threadLocal);
                threadLocalHolder = newHolder;
                return true;
            }
        }

        // 默认的拷贝器，影子拷贝，直接返回父值
        private static final TtlCopier<Object> shadowCopier = new TtlCopier<Object>() {
            @Override
            public Object copy(Object parentValue) {
                return parentValue;
            }
        };

        //私有构造，说明只能通过静态方法提供外部调用
        private Transmitter() {
            throw new InstantiationError("Must not instantiate this class");
        }
    }
}
