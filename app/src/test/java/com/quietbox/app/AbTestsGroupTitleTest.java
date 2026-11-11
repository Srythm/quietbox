package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * F337：AbTests.groupTitle 不得把未知分组静默归为「其他推荐」。
 *
 * <p>分组号是写死的数据，新增 GROUP_* 或填错 group 时必须在开发/测试期暴露，
 * 而不是让用户看到错误归类。
 */
public class AbTestsGroupTitleTest {

    @Test
    public void knownGroupsMapToTheirOwnTitle() {
        assertEquals(R.string.ab_group_detail, AbTests.groupTitle(AbTest.GROUP_DETAIL));
        assertEquals(R.string.ab_group_playback, AbTests.groupTitle(AbTest.GROUP_PLAYBACK));
        assertEquals(R.string.ab_group_misc, AbTests.groupTitle(AbTest.GROUP_MISC));
    }

    @Test
    public void unknownGroupFailsLoudly() {
        try {
            AbTests.groupTitle(Integer.MAX_VALUE);
            fail("未知分组不得静默归为其他推荐");
        } catch (IllegalArgumentException expected) {
            // 预期：分组错误在开发/测试期暴露。
        }
    }

    /** 现有精选条目不得使用未声明的分组号。 */
    @Test
    public void everyCuratedTestUsesAKnownGroup() {
        for (AbTest test : AbTests.ALL) {
            AbTests.groupTitle(test.group); // 未声明分组会抛 IllegalArgumentException
        }
    }
}
