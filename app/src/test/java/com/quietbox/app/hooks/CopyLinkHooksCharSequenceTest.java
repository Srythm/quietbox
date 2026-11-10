package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * F088：剪贴板/副挂 text 是 CharSequence，宿主可能传 Spanned/富文本（非 String）。
 * 旧实现 instanceof String 对这类值 fail-open，追踪参数不被净化。
 * normalizeCopyText 是纯函数，直接断言非 String 的 CharSequence 也能归一化并净化，
 * 不需要 Android ClipData/Xposed 运行时。
 */
public class CopyLinkHooksCharSequenceTest {

    /** 明确的非 String CharSequence（模拟 Spanned/富文本 item 的文本）。 */
    private static CharSequence spanned(String raw) {
        return new StringBuilder(raw);
    }

    @Test
    public void nonStringCharSequenceIsNormalized() {
        String url = "https://www.xiaohongshu.com/discovery/item/66f?share_id=abc&foo=bar";
        CharSequence text = spanned(url);
        assertEquals("非 String 的 CharSequence 不得被丢弃",
                url, CopyLinkHooks.normalizeCopyText(text));
    }

    /** 端到端：富文本 text 经归一化 + 净化后追踪参数必须被删除。 */
    @Test
    public void spannedTextStillGetsSanitized() {
        String url = "https://www.xiaohongshu.com/discovery/item/66f?"
                + "share_id=abc&xhsshare=Weixin&sid=deadbeef&foo=bar";
        String raw = CopyLinkHooks.normalizeCopyText(spanned(url));
        assertEquals("富文本 URL 不该是 null", url, raw);
        String clean = CopyLinkHooks.sanitizeCopy(raw, "ug-share-copy-link", false);
        assertEquals("https://www.xiaohongshu.com/discovery/item/66f?foo=bar", clean);
    }

    @Test
    public void plainStringStillWorks() {
        assertEquals("abc", CopyLinkHooks.normalizeCopyText("abc"));
    }

    @Test
    public void nullAndEmptyAreIgnored() {
        assertNull(CopyLinkHooks.normalizeCopyText(null));
        assertNull(CopyLinkHooks.normalizeCopyText(""));
        assertNull("空 CharSequence 也不净化", CopyLinkHooks.normalizeCopyText(new StringBuilder()));
    }
}
