package top.wkbin.zaomeng.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import top.wkbin.zaomeng.data.api.CreateRunRequest
import top.wkbin.zaomeng.data.api.DeleteRunResponse
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.ktor.services.RunManagementService
import top.wkbin.zaomeng.ktor.services.RunPackageService
import top.wkbin.zaomeng.ktor.services.StorageService

/**
 * 书卷管理（创建 / 导入 / 读取 / 删除 / 停止）：**进程内直连**（原 `KtorRunManagementClient`）。
 *
 * 两处需要留意、代码里已保留原语义的地方：
 *
 * 1. `get(runId)` 对应的路由其实在 `RunsRoute` 而不是 `RunManagementRoute`，
 *    它读 manifest 后会**注入实时 `avatar_version`**（`withLiveAvatarVersions`）。
 *    这里照做，否则头像会一直显示旧版本。
 * 2. `create` / `delete` / `stop` 原先在路由里把异常改写成固定的英文文案
 *    （"Failed to create run" 等）。进程内直连后不再吞掉原始异常——
 *    用户看到的是真实原因而非笼统失败语，重试判据（statusCode 为空即可重试）不受影响。
 */
class RunManagementClient(
    private val runService: RunManagementService,
    private val packageService: RunPackageService,
    private val storage: StorageService,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun create(payload: CreateRunRequest): RunManifestDto = json.decodeFromJsonElement(
        runService.createRun(
            novelName = payload.novelName,
            novelContentBase64 = payload.novelContentBase64,
            characters = payload.characters,
            maxSentences = payload.maxSentences,
            maxChars = payload.maxChars,
            autoRun = payload.autoRun,
            deferRun = payload.deferRun,
            origin = payload.origin,
        ),
    )

    suspend fun import(payload: ImportRunPackageRequest): RunManifestDto =
        json.decodeFromJsonElement(packageService.importPackage(payload))

    /** 对应 `GET /api/web/runs/{run_id}`：读 manifest + 注入实时 avatar_version。 */
    suspend fun get(runId: String): RunManifestDto {
        val manifest = storage.readRunManifest(runId) ?: throw NoSuchElementException("Run not found")
        return json.decodeFromJsonElement(storage.withLiveAvatarVersions(manifest, runId))
    }

    suspend fun delete(runId: String): DeleteRunResponse =
        json.decodeFromJsonElement(runService.deleteRun(runId))

    suspend fun stop(runId: String): RunManifestDto =
        json.decodeFromJsonElement(runService.stopRun(runId))
}
