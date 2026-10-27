package com.limao.unzip.engine.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** E05：Zip Slip 与路径规范化。 */
class PathGuardTest {

    @Test
    fun `normalizes separators and strips leading slash`() {
        assertEquals("a/b/c.txt", PathGuard.sanitize("a\\b/c.txt"))
        assertEquals("a/b/c.txt", PathGuard.sanitize("/a/b/c.txt"))
        assertEquals("a/b/c.txt", PathGuard.sanitize("C:/a/b/c.txt"))
        assertEquals("a/b", PathGuard.sanitize("a/./b"))
    }

    @Test
    fun `rejects traversal and absolute escapes`() {
        assertNull(PathGuard.sanitize("../evil.txt"))
        assertNull(PathGuard.sanitize("a/../../evil.txt"))
        assertNull(PathGuard.sanitize("a\\..\\..\\evil.txt"))
        assertNull(PathGuard.sanitize(".."))
        assertNull(PathGuard.sanitize("/"))
        assertNull(PathGuard.sanitize(""))
    }

    @Test
    fun `rejects control chars and overlong names`() {
        assertNull(PathGuard.sanitize("a\u0000b"))
        assertNull(PathGuard.sanitize("a/b\u001Bc"))
        assertNull(PathGuard.sanitize("a/".padEnd(PathGuard.MAX_PATH + 10, 'x')))
        assertNull(PathGuard.sanitize("a/" + "x".repeat(300)))
    }

    @Test
    fun `keeps legal unicode names`() {
        assertEquals("测试目录/文件 β.txt", PathGuard.sanitize("测试目录/文件 β.txt"))
        assertEquals("a b/c-d_e.f", PathGuard.sanitize("a b/c-d_e.f"))
    }
}
