package com.alibaba.ttl;

/**
 * TtlCopier creates the value when {@link TransmittableThreadLocal.Transmitter#capture()},
 * use the created value when {@link TransmittableThreadLocal.Transmitter#replay(Object)}
 *
 * @see TransmittableThreadLocal.Transmitter
 * @see TransmittableThreadLocal.Transmitter#capture()
 * @author Jerry Lee (oldratlee at gmail dot com)
 * @since 2.11.0
 *
 * 函数式接口，TTL拷贝器
 */
@FunctionalInterface
public interface TtlCopier<T> {
    /**
     * Computes the value for {@link TransmittableThreadLocal}
     * or registered {@link ThreadLocal}(registered by method {@link TransmittableThreadLocal.Transmitter#registerThreadLocal Transmitter#registerThreadLocal})
     * as a function of the source thread's value at the time the task
     * Object is created.
     * <p>
     * This method is called from {@link TtlRunnable} or
     * {@link TtlCallable} when it create, before the task is started
     * (aka. called when {@link TransmittableThreadLocal.Transmitter#capture()}).
     *
     * @see TransmittableThreadLocal.Transmitter#registerThreadLocal(ThreadLocal, TtlCopier)
     * @see TransmittableThreadLocal.Transmitter#registerThreadLocalWithShadowCopier(ThreadLocal)
     * @see TransmittableThreadLocal.Transmitter#unregisterThreadLocal
     *
     * 拷贝父属性
     */
    T copy(T parentValue);
}
