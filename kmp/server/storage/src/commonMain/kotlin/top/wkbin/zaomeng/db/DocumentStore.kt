package top.wkbin.zaomeng.db

import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import top.wkbin.zaomeng.platform.monotonicNanos
import top.wkbin.zaomeng.platform.nowEpochMillis
import top.wkbin.zaomeng.platform.runBlockingPlatform

/**
 * 文档存储后端抽象：StorageService 的业务文件语义（文本/二进制、目录、递归删除、改名）。
 *
 * - [FileSystemDocumentStore]：原 JSON 文件行为（测试/开发用）。
 * - [RoomDocumentStore]：生产环境统一走 Room（SQLite）。
 */
interface DocumentStore {
    fun readBytes(path: Path): ByteArray?

    fun writeBytes(path: Path, bytes: ByteArray, updatedAtMillis: Long)

    fun updatedAtMillis(path: Path): Long?

    /** 文件或目录是否存在。 */
    fun exists(path: Path): Boolean

    fun isFile(path: Path): Boolean

    fun isDirectory(path: Path): Boolean

    /** 列出直接子项（一层；返回完整子路径）。 */
    fun listFiles(path: Path): List<Path>

    fun mkdirs(path: Path)

    fun deleteFile(path: Path)

    fun deleteRecursively(path: Path)

    fun rename(source: Path, target: Path): Boolean

    fun fileSize(path: Path): Long
}

/** 文件系统后端：保留原有 okio 文件行为（含临时文件 + 原子 rename 写入）。 */
class FileSystemDocumentStore(
    private val fs: FileSystem = FileSystem.SYSTEM,
) : DocumentStore {
    override fun readBytes(path: Path): ByteArray? {
        if (!exists(path)) return null
        return runCatching { fs.source(path).buffer().use { it.readByteArray() } }.getOrNull()
    }

    override fun writeBytes(path: Path, bytes: ByteArray, updatedAtMillis: Long) {
        runCatching { fs.createDirectories(path.parent!!) }
        val temp = path.parent!! / ".${path.name}.${monotonicNanos()}.tmp"
        fs.sink(temp).buffer().use { sink -> sink.write(bytes) }
        if (!replace(temp, path)) {
            runCatching { fs.delete(path) }
            if (!replace(temp, path)) {
                runCatching { fs.delete(temp) }
                throw IllegalStateException("Unable to replace ${path.name}")
            }
        }
    }

    private fun replace(source: Path, target: Path): Boolean = try {
        fs.atomicMove(source, target)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * `metadataOrNull` 在 Windows 上**先做路径转换**，遇到本平台无法表示的路径会抛
     * `InvalidPathException` 而不是返回 null。真实触发场景：旧书卷的 `artifact_index`
     * 里角色名带引号（`"陆城"`），据此拼出的 `artifacts/characters/"陆城"/PROFILE.md`
     * 在 Windows 上无法表示，于是「查人设档案」这一步直接崩掉整轮对话生成。
     *
     * 这里的语义是「这个路径是否指向已存在的文件/目录」——无法表示当然就是不存在，
     * 因此把异常收敛成 null/false，而不是让它打断调用链（Linux 上该字符合法，
     * 同一段代码只会静默返回 false，两端行为由此一致）。
     */
    private fun metadataOrNull(path: Path) = runCatching { fs.metadataOrNull(path) }.getOrNull()

    override fun updatedAtMillis(path: Path): Long? = metadataOrNull(path)?.lastModifiedAtMillis

    override fun exists(path: Path): Boolean = runCatching { fs.exists(path) }.getOrDefault(false)

    override fun isFile(path: Path): Boolean = metadataOrNull(path)?.isRegularFile == true

    override fun isDirectory(path: Path): Boolean = metadataOrNull(path)?.isDirectory == true

    override fun listFiles(path: Path): List<Path> =
        if (exists(path)) runCatching { fs.list(path) }.getOrDefault(emptyList()) else emptyList()

    override fun mkdirs(path: Path) {
        fs.createDirectories(path)
    }

    override fun deleteFile(path: Path) {
        runCatching { fs.delete(path) }
    }

    override fun deleteRecursively(path: Path) {
        runCatching { fs.deleteRecursively(path) }
    }

    override fun rename(source: Path, target: Path): Boolean = try {
        fs.atomicMove(source, target)
        true
    } catch (e: Exception) {
        false
    }

    override fun fileSize(path: Path): Long = fs.metadataOrNull(path)?.size ?: 0L
}

/**
 * Room 后端：文档行按虚拟路径存取。
 *
 * Room DAO 是 suspend，服务层保持同步文件语义，这里用平台 runBlocking 桥接
 * （SQLite 操作在 Room 的 IO 执行器上跑，调用线程阻塞等待，等价于原同步文件 IO）。
 */
class RoomDocumentStore(
    private val dao: DocumentDao,
) : DocumentStore {
    /** 统一用 / 作路径分隔符（Windows 的 okio Path toString 是 \，前缀匹配需要一致）。 */
    private fun key(path: Path): String = path.toString().replace('\\', '/')

    private fun prefixOf(path: Path): String = key(path) + "/"

    /** LIKE 前缀 pattern：转义 %、_ 与转义符本身。 */
    private fun patternFor(prefix: String): String =
        prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"

    override fun readBytes(path: Path): ByteArray? =
        runBlockingPlatform { dao.bytesOf(key(path)) }

    override fun writeBytes(path: Path, bytes: ByteArray, updatedAtMillis: Long) {
        runBlockingPlatform { dao.upsert(DocumentEntity(key(path), bytes, updatedAtMillis)) }
    }

    override fun updatedAtMillis(path: Path): Long? =
        runBlockingPlatform { dao.updatedAtOf(key(path)) }

    override fun exists(path: Path): Boolean {
        val k = key(path)
        return runBlockingPlatform { dao.exists(k) || dao.existsUnder(patternFor(prefixOf(path))) }
    }

    override fun isFile(path: Path): Boolean =
        runBlockingPlatform { dao.exists(key(path)) }

    override fun isDirectory(path: Path): Boolean =
        runBlockingPlatform { dao.existsUnder(patternFor(prefixOf(path))) }

    override fun listFiles(path: Path): List<Path> {
        val prefix = prefixOf(path)
        val keys = runBlockingPlatform { dao.pathsUnder(patternFor(prefix)) }
        return keys.mapNotNull { k ->
            val rest = k.removePrefix(prefix)
            val segment = rest.substringBefore('/')
            if (segment.isEmpty()) null else path / segment
        }.distinct().sortedBy { it.toString() }
    }

    override fun mkdirs(path: Path) {
        // 目录隐式存在，无需落行
    }

    override fun deleteFile(path: Path) {
        runBlockingPlatform { dao.delete(key(path)) }
    }

    override fun deleteRecursively(path: Path) {
        runBlockingPlatform {
            dao.delete(key(path))
            dao.deleteUnder(patternFor(prefixOf(path)))
        }
    }

    override fun rename(source: Path, target: Path): Boolean = try {
        val sourceKey = key(source)
        val sourcePrefix = prefixOf(source)
        val moves: List<Pair<String, ByteArray>> = runBlockingPlatform {
            val self = dao.bytesOf(sourceKey)?.let { listOf(sourceKey to it) } ?: emptyList()
            val under = dao.pathsUnder(patternFor(sourcePrefix))
            self + under.map { it to (dao.bytesOf(it) ?: ByteArray(0)) }
        }
        if (moves.isEmpty()) return false
        val targetKey = key(target)
        val targetPrefix = prefixOf(target)
        val now = nowEpochMillis()
        runBlockingPlatform {
            moves.forEach { (from, bytes) ->
                val to = if (from == sourceKey) {
                    targetKey
                } else {
                    targetPrefix + from.removePrefix(sourcePrefix)
                }
                dao.upsert(DocumentEntity(to, bytes, now))
            }
            moves.forEach { (from, _) -> dao.delete(from) }
        }
        true
    } catch (e: Exception) {
        false
    }

    override fun fileSize(path: Path): Long =
        runBlockingPlatform { dao.bytesOf(key(path))?.size?.toLong() ?: 0L }
}
