package top.wkbin.zaomeng.platform

/**
 * 数据损坏审计（R6）使用的固定事件类别。
 *
 * 为什么要单独一个文件：生产方在 `:server:storage`（`StorageService` 的写入审计），
 * 消费方在 `:server` 的诊断审计，两边必须用**同一个字符串**，
 * 否则「写入失败」事件会被审计当成普通事件忽略掉——文件坏了却查不出为什么。
 *
 * 放在 `core/runtime` 是因为两边都能依赖它，而它本身不依赖任何业务类型。
 */
object DataIntegrityCategories {
    /** 业务写入失败（磁盘满 / 权限 / 并发写冲突）。 */
    const val WRITE_FAILURE = "data.write_failure"

    /** 关键文件解析失败（半截写入 / 编码损坏）。 */
    const val PARSE_FAILURE = "data.parse_failure"
}
