package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.Path
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 诊断报告必须回答「这个 run 为什么失败」。
 *
 * 蒸馏失败时 [DistillExecutor.fail] 把原因写进 `progress.error`，而不是顶层
 * `error.type`。此前导出只读后者，于是所有失败 run 的 errorType 都是空字符串——
 * 用户拿到的诊断包里只看得到「失败了」，看不到「为什么」。
 */
class DiagnosticsRunSummaryTest {

    private fun serviceWithManifest(manifest: kotlinx.serialization.json.JsonObject): DiagnosticsService {
        val directory: Path = createTempDirectory("zaomeng-diag-run").toString().toPath()
        val storage = StorageService(directory)
        storage.writeRunManifest("run-1", manifest)
        return DiagnosticsService(storageRoot = directory, storageService = storage)
    }

    private fun DiagnosticsService.firstRun(): RunSummary {
        val report = buildDiagnosticsReport()
        assertTrue(report.runs.isNotEmpty(), "报告必须包含 run 摘要")
        return report.runs.first()
    }

    @Test
    fun `distillation failure reason is surfaced from progress error`() {
        val summary = serviceWithManifest(
            buildJsonObject {
                put("run_id", "run-1")
                put("status", "failed")
                put("updated_at", "2026-10-13T00:00:00+08:00")
                putJsonObject("progress") {
                    put("stage", "failed")
                    put("error", "模型响应超时：300 秒内模型未返回结果")
                }
            },
        ).firstRun()

        assertEquals("failed", summary.status)
        assertEquals("failed", summary.stage)
        assertEquals("模型响应超时：300 秒内模型未返回结果", summary.errorMessage)
    }

    @Test
    fun `structured error message wins over the progress fallback`() {
        val summary = serviceWithManifest(
            buildJsonObject {
                put("run_id", "run-1")
                put("status", "failed")
                putJsonObject("error") {
                    put("type", "rate_limit")
                    put("message", "429 Too Many Requests")
                }
                putJsonObject("progress") {
                    put("stage", "failed")
                    put("error", "旧的回退文案")
                }
            },
        ).firstRun()

        assertEquals("rate_limit", summary.errorType)
        assertEquals("429 Too Many Requests", summary.errorMessage)
    }

    @Test
    fun `successful runs carry no error message`() {
        val summary = serviceWithManifest(
            buildJsonObject {
                put("run_id", "run-1")
                put("status", "completed")
                putJsonObject("progress") { put("stage", "completed") }
            },
        ).firstRun()

        assertEquals("completed", summary.status)
        assertEquals("", summary.errorMessage)
        assertEquals("", summary.errorType)
    }

    @Test
    fun `character count comes from the artifact index`() {
        val summary = serviceWithManifest(
            buildJsonObject {
                put("run_id", "run-1")
                put("status", "completed")
                putJsonObject("artifact_index") {
                    put("characters", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("甲"))
                        add(kotlinx.serialization.json.JsonPrimitive("乙"))
                    })
                }
            },
        ).firstRun()

        assertEquals(2, summary.characterCount)
    }
}
