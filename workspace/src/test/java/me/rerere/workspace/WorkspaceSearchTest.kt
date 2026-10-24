package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceSearchTest {

    private fun tempRoot(): File = Files.createTempDirectory("workspace-search").toFile()

    private fun tree(fileSystem: WorkspaceFileSystem, root: File) {
        fileSystem.writeText(root, "src/main/kotlin/MainActivity.kt", "class MainActivity")
        fileSystem.writeText(root, "src/main/kotlin/MainViewModel.kt", "class MainViewModel")
        fileSystem.writeText(root, "docs/main_guide.md", "guide")
        fileSystem.writeText(root, "docs/README.md", "readme")
        fileSystem.writeText(root, ".l2s.tmp/scratch.txt", "temp")
    }

    // region 基本匹配

    @Test
    fun `plain text query matches by substring across all subdirectories`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        tree(fs, root)

        val hits = fs.search(root, "main").entries.map { it.path }.sorted()
        // 默认含目录：src/main 这个目录名本身就是命中项
        assertEquals(
            listOf(
                "docs/main_guide.md",
                "src/main",
                "src/main/kotlin/MainActivity.kt",
                "src/main/kotlin/MainViewModel.kt",
            ),
            hits,
        )
    }

    @Test
    fun `query is case insensitive`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        tree(fs, root)

        assertEquals(
            fs.search(root, "main").entries.map { it.path }.sorted(),
            fs.search(root, "MAIN").entries.map { it.path }.sorted(),
        )
    }

    @Test
    fun `wildcard query falls back to glob`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        tree(fs, root)

        assertEquals(
            listOf("src/main/kotlin/MainActivity.kt", "src/main/kotlin/MainViewModel.kt").sorted(),
            fs.search(root, "*.kt").entries.map { it.path }.sorted(),
        )
    }

    @Test
    fun `blank query returns nothing instead of scanning everything`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        tree(fs, root)

        assertTrue(fs.search(root, "").entries.isEmpty())
        assertTrue(fs.search(root, "   ").entries.isEmpty())
    }

    // endregion

    // region 排序与范围

    @Test
    fun `results are ranked exact then prefix then suffix then contains`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        fs.writeText(root, "report.txt", "a")
        fs.writeText(root, "report-2024.txt", "b")
        fs.writeText(root, "annual-report.txt", "c")
        fs.writeText(root, "zzz-report-qqq.txt", "d")

        assertEquals(
            listOf("report.txt", "report-2024.txt", "annual-report.txt", "zzz-report-qqq.txt"),
            fs.search(root, "report").entries.map { it.path },
        )
    }

    @Test
    fun `directories can be excluded`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        fs.writeText(root, "notes/todo.txt", "x")

        val withDirs = fs.search(root, "notes", includeDirectories = true).entries.map { it.path }
        assertTrue(withDirs.contains("notes"))

        val filesOnly = fs.search(root, "notes", includeDirectories = false).entries.map { it.path }
        assertFalse(filesOnly.contains("notes"))
    }

    @Test
    fun `path argument limits the search to a subtree`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        tree(fs, root)

        val inDocs = fs.search(root, "main", path = "docs").entries.map { it.path }
        // 从子树搜起，但 path 仍然相对区域根，可直接用于打开/预览
        assertEquals(listOf("docs/main_guide.md"), inDocs)
    }

    @Test
    fun `internal temp entries are never returned`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        tree(fs, root)

        assertTrue(fs.search(root, "scratch").entries.isEmpty())
        assertTrue(fs.search(root, ".l2s").entries.isEmpty())
    }

    @Test
    fun `search start directory itself is not listed as a hit`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        fs.writeText(root, "proj/a.txt", "x")
        fs.writeText(root, "proj/proj.cfg", "y")

        val paths = fs.search(root, "proj", path = "proj").entries.map { it.path }
        assertFalse("起点目录自身不该出现", paths.contains("proj"))
        assertEquals(listOf("proj/proj.cfg"), paths)
    }

    // endregion

    // region 上限保护

    @Test
    fun `limit caps results and flags truncation`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        repeat(50) { fs.writeText(root, "big/item$it.txt", "x") }

        val result = fs.search(root, "item", limit = 5)
        assertEquals(5, result.entries.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `visit cap stops an unbounded walk and reports truncation`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem(config = WorkspaceConfig(maxSearchVisit = 12))
        repeat(60) { fs.writeText(root, "deep/item$it.txt", "x") }

        val result = fs.search(root, "nomatchatall")
        assertTrue(result.entries.isEmpty())
        assertTrue("遍历到上限必须标记 truncated，否则用户以为真的没有", result.truncated)
    }

    @Test
    fun `missing directory returns empty instead of throwing`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        assertTrue(fs.search(root, "anything", path = "nope").entries.isEmpty())
    }

    // endregion

    // region 与 Manager 的联动

    @Test
    fun `manager search honours storage area`() {
        val base = tempRoot()
        val manager = WorkspaceManager(base)
        val root = "ws"
        manager.ensureWorkspace(root)

        File(manager.filesDir(root), "alpha.txt").writeText("x")
        File(manager.linuxDir(root), "beta.txt").writeText("y")

        val files = manager.searchFiles(root, "alpha", area = WorkspaceStorageArea.FILES).entries
        assertEquals(listOf("alpha.txt"), files.map { it.path })

        val linux = manager.searchFiles(root, "beta", area = WorkspaceStorageArea.LINUX).entries
        assertEquals(listOf("beta.txt"), linux.map { it.path })

        // 区域之间互相看不见
        assertTrue(manager.searchFiles(root, "beta", area = WorkspaceStorageArea.FILES).entries.isEmpty())
    }

    @Test
    fun `search results carry usable relative paths`() {
        val root = tempRoot()
        val fs = WorkspaceFileSystem()
        tree(fs, root)

        val hit = fs.search(root, "MainActivity").entries.single()
        assertEquals("MainActivity.kt", hit.name)
        assertFalse(hit.isDirectory)
        assertTrue(hit.sizeBytes > 0)
        // 路径不带前导斜杠，可直接喂给 list / readText / open
        assertFalse(hit.path.startsWith("/"))
    }

    // endregion
}
