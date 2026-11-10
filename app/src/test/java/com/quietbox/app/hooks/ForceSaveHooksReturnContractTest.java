package com.quietbox.app.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

/**
 * F101：门 2（FunctionSwitch#a()Z）的返回值与异常日志契约。
 *
 * <p>宿主调用方直接 {@code !functionSwitch.a()} 拆箱，Hooker 若把 proceed 的
 * 非 Boolean 原值（含 null）return 回去，会在宿主侧 NPE；反射 invoke 失败抛
 * InvocationTargetException 时，真实原因被包装层掩盖。两个纯函数分别覆盖这两点。
 */
public class ForceSaveHooksReturnContractTest {

    @Test
    public void booleanOriginalIsPassedThrough() {
        assertTrue(ForceSaveHooks.originalOrFalse(Boolean.TRUE));
        assertFalse(ForceSaveHooks.originalOrFalse(Boolean.FALSE));
    }

    @Test
    public void nullOrNonBooleanOriginalFailsOpenToFalse() {
        assertFalse("proceed 返回 null 时不得把 null 交给宿主拆箱",
                ForceSaveHooks.originalOrFalse(null));
        assertFalse(ForceSaveHooks.originalOrFalse("true"));
        assertFalse(ForceSaveHooks.originalOrFalse(1));
        assertFalse(ForceSaveHooks.originalOrFalse(new Object()));
    }

    @Test
    public void invocationTargetExceptionIsUnwrapped() {
        IllegalStateException cause = new IllegalStateException("host boom");
        Throwable unwrapped = ForceSaveHooks.unwrapInvocation(
                new InvocationTargetException(cause));
        assertSame("必须记录反射目标的真实异常原因", cause, unwrapped);
    }

    @Test
    public void plainThrowableAndCauselessInvocationTargetAreKept() {
        IOException plain = new IOException("io");
        assertSame("普通异常原样返回", plain, ForceSaveHooks.unwrapInvocation(plain));
        InvocationTargetException noCause = new InvocationTargetException(null);
        assertSame("无 cause 的包装异常本身不得丢失",
                noCause, ForceSaveHooks.unwrapInvocation(noCause));
    }
}
