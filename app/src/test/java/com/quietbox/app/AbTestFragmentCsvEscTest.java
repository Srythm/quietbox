package com.quietbox.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * F336：CSV 单元格内的换行必须压成空格，否则 countRows 会把内嵌换行多计一条。
 */
public class AbTestFragmentCsvEscTest {

    @Test
    public void lineBreaksAreFlattened() {
        assertEquals("a b", AbTestFragment.esc("a\nb"));
        assertEquals("a b", AbTestFragment.esc("a\r\nb"));
        assertEquals("a b", AbTestFragment.esc("a\rb"));
        assertEquals("plain", AbTestFragment.esc("plain"));
    }

    @Test
    public void formulaGuardStillRunsBeforeFlattening() {
        assertEquals("'\r 开头仍按公式注入处理", "' x", AbTestFragment.esc("\rx"));
    }
}
