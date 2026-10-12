package top.wkbin.zaomeng.platform

/** ZIP 归档文件条目（目录条目已过滤）。 */
data class ZipFileEntryData(
    val name: String,
    val content: ByteArray,
)

/** 读取 zip 归档全部文件条目（Android/JVM 均走 java.util.zip）。 */
expect fun readZipFileEntries(bytes: ByteArray): List<ZipFileEntryData>
