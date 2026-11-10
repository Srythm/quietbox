package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.TestPaths;
import com.quietbox.app.anchor.AnchorKeys;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * NoteUnavailableHooks.readString 的 JVM 单测。
 *
 * 9.52.0 的 NoteFeed 混淆 getter c1/u0 被 R8 改成了别的返回类型，
 * 但 modelType/id 字段名稳定，所以取值策略是“字段优先、getter 兜底”。
 */
public class NoteUnavailableHooksTest {

    /** 字段优先：即使同名 getter 返回别的值，也以字段为准。 */
    static class FieldWins {
        String modelType = "error";

        public String c1() {
            return "note";
        }
    }

    /** 字段缺失：回退旧 getter。 */
    static class GetterFallback {
        public String c1() {
            return "note";
        }
    }

    /** 字段为 null：回退旧 getter。 */
    static class NullFieldFallsBack {
        String modelType;

        public String c1() {
            return "note";
        }
    }

    /** 字段存在但类型不符：返回 null，不猜 String.valueOf。 */
    static class NonStringField {
        int modelType = 1;

        public String c1() {
            return "note";
        }
    }

    /** 父类字段也要能找到（与旧 getMethod 的继承查找一致）。 */
    static class Base {
        String id = "note-id";
    }

    static class Child extends Base {
    }

    /** 字段和 getter 都没有：null。 */
    static class Empty {
    }

    @Test
    public void fieldTakesPrecedenceOverGetter() {
        assertEquals("error", NoteUnavailableHooks.readString(new FieldWins(), "modelType", "c1"));
    }

    @Test
    public void missingFieldFallsBackToGetter() {
        assertEquals("note", NoteUnavailableHooks.readString(new GetterFallback(), "modelType", "c1"));
    }

    @Test
    public void nullFieldFallsBackToGetter() {
        assertEquals("note", NoteUnavailableHooks.readString(new NullFieldFallsBack(), "modelType", "c1"));
    }

    @Test
    public void nonStringFieldReturnsNullWithoutGuessing() {
        assertNull(NoteUnavailableHooks.readString(new NonStringField(), "modelType", "c1"));
    }

    @Test
    public void inheritedFieldIsFound() {
        assertEquals("note-id", NoteUnavailableHooks.readString(new Child(), "id", "u0"));
    }

    @Test
    public void nullTargetAndMissingMembersReturnNull() {
        assertNull(NoteUnavailableHooks.readString(null, "modelType", "c1"));
        assertNull(NoteUnavailableHooks.readString(new Empty(), "modelType", "c1"));
    }

    /**
     * F-065：目标类名唯一来源是 anchors.json 的 classes 段（经 AnchorKeys 常量），
     * 本文件不得再散落硬编码，也不得保留从未使用的 FEED 死代码。
     */
    @Test
    public void targetClassNamesComeFromAnchorTableNotHardcoded() throws IOException {
        Path source = TestPaths.resolveFromCwd(
                "src/main/java/com/quietbox/app/hooks/NoteUnavailableHooks.java",
                "app/src/main/java/com/quietbox/app/hooks/NoteUnavailableHooks.java",
                "project/app/src/main/java/com/quietbox/app/hooks/NoteUnavailableHooks.java",
                "QuietBox/project/app/src/main/java/com/quietbox/app/hooks/NoteUnavailableHooks.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertFalse("不得硬编码 NoteDetailRepository 类名",
                text.contains("\"com.xingin.matrix.notedetail"));
        assertFalse("不得硬编码 DetailNoteFeedHolder 类名",
                text.contains("\"com.xingin.notebase.entities"));
        assertFalse("FEED 死代码必须删除", text.contains("\"com.xingin.entities.notedetail.NoteFeed\""));
        assertTrue("必须引用 AnchorKeys.NOTE_DETAIL_REPOSITORY",
                text.contains("AnchorKeys.NOTE_DETAIL_REPOSITORY"));
        assertTrue("必须引用 AnchorKeys.NOTE_DETAIL_HOLDER",
                text.contains("AnchorKeys.NOTE_DETAIL_HOLDER"));
        assertTrue("必须经 AnchorTable.className 读取 classes 段的 FQN",
                text.contains("anchors.className"));
    }

    /** anchorClassName 对 null 表必须 fail-open 返回空串，不抛异常。 */
    @Test
    public void anchorClassNameFailsOpenForMissingTable() {
        assertEquals("", NoteUnavailableHooks.anchorClassName(null, AnchorKeys.NOTE_DETAIL_HOLDER));
    }

    /** NoteFeed 的 FEED 常量确属死代码：AnchorKeys 定义了它但本文件不再引用。 */
    @Test
    public void noteFeedAnchorKeyExistsButIsNotReferencedByHook() throws IOException {
        assertEquals("note_feed", AnchorKeys.NOTE_FEED);
        Path source = TestPaths.resolveFromCwd(
                "src/main/java/com/quietbox/app/hooks/NoteUnavailableHooks.java",
                "app/src/main/java/com/quietbox/app/hooks/NoteUnavailableHooks.java",
                "project/app/src/main/java/com/quietbox/app/hooks/NoteUnavailableHooks.java",
                "QuietBox/project/app/src/main/java/com/quietbox/app/hooks/NoteUnavailableHooks.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertFalse("未使用的 FEED 锚点常量不得被本文件引用",
                text.contains("AnchorKeys.NOTE_FEED"));
    }
}
