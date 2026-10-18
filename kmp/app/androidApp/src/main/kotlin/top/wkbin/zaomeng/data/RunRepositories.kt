package top.wkbin.zaomeng.data

import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import top.wkbin.zaomeng.client.platform.clientBase64Encode
import top.wkbin.zaomeng.data.api.CreateCrossoverSpaceRequest
import top.wkbin.zaomeng.data.api.CreateRunRequest
import top.wkbin.zaomeng.data.api.CrossoverParticipantRequest
import top.wkbin.zaomeng.data.api.DeleteRunResponse
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.EstimateSamplingRequest
import top.wkbin.zaomeng.data.api.ExportedRunPackage
import top.wkbin.zaomeng.data.api.ExtractMainCharactersRequest
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.data.local.RelationsClient
import top.wkbin.zaomeng.data.local.RunManagementClient
import top.wkbin.zaomeng.client.platform.clientMonotonicNanos
import top.wkbin.zaomeng.data.local.RunOpsClient
import top.wkbin.zaomeng.data.local.RunsClient
import top.wkbin.zaomeng.data.local.WorldMemoryClient
import top.wkbin.zaomeng.data.api.LibraryPackageImportDto
import top.wkbin.zaomeng.data.api.NovelChaptersResponse
import top.wkbin.zaomeng.data.api.RedistillSuggestionsDto
import top.wkbin.zaomeng.data.api.RelationDetailsDto
import top.wkbin.zaomeng.data.api.RelationItemDto
import top.wkbin.zaomeng.data.api.RestartRunRequest
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SamplingPlanDto
import top.wkbin.zaomeng.data.api.SaveWorldFactRequest
import top.wkbin.zaomeng.data.api.SuggestRedistillSegmentsRequest
import top.wkbin.zaomeng.data.api.UpdateRelationDetailRequest
import top.wkbin.zaomeng.data.api.WorldFactDto
import top.wkbin.zaomeng.data.api.WorldMemoryDto
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.domain.distill.DistillPlanningGateway

class RunRepositoryImpl(
    private val runs: RunsClient,
    private val runManagement: RunManagementClient,
    private val runOps: RunOpsClient,
    private val appPreferences: AppPreferencesRepository,
) : RunRepository, DistillPlanningGateway {
    override suspend fun listRuns(): List<RunManifestDto> = repositoryRequest {
        runs.list().items
    }

    override suspend fun createNovel(
        filename: String,
        bytes: ByteArray,
        characters: List<String>,
        maxSentences: Int,
        maxChars: Int,
        autoRun: Boolean,
        origin: String,
    ): RunManifestDto {
        val payload = withContext(Dispatchers.Default) {
            CreateRunRequest(
                novelName = filename,
                novelContentBase64 = clientBase64Encode(bytes),
                characters = characters,
                maxSentences = maxSentences,
                maxChars = maxChars,
                autoRun = autoRun,
                deferRun = !autoRun,
                origin = origin,
            )
        }
        return repositoryRequest {
            val run = runManagement.create(payload)
            appPreferences.rememberRun(run.runId)
            run
        }
    }

    override suspend fun estimateSampling(
        charCount: Int,
        sentenceCount: Int,
        characterCount: Int,
        maxSentences: Int,
        maxChars: Int,
    ): SamplingPlanDto = repositoryRequest {
        runOps.estimateSampling(
            EstimateSamplingRequest(
                charCount = charCount,
                sentenceCount = sentenceCount,
                characterCount = characterCount,
                maxSentences = maxSentences,
                maxChars = maxChars,
            ),
        )
    }

    override suspend fun extractMainCharacters(
        novelName: String,
        novelBytes: ByteArray,
        maxCharacters: Int,
    ): List<String> {
        val payload = withContext(Dispatchers.Default) {
            ExtractMainCharactersRequest(
                novelName = novelName,
                novelContentBase64 = clientBase64Encode(novelBytes),
                maxCharacters = maxCharacters,
            )
        }
        return repositoryRequest { runOps.extractMainCharacters(payload).characters }
    }

    override suspend fun extractRunMainCharacters(
        runId: String,
        maxCharacters: Int,
    ): List<String> = repositoryRequest {
        runOps.extractRunCharacters(runId, maxCharacters).characters
    }

    override suspend fun listNovelChapters(runId: String): NovelChaptersResponse = repositoryRequest {
        runOps.listNovelChapters(runId)
    }

    override suspend fun importPackage(
        filename: String,
        bytes: ByteArray,
        libraryPackage: LibraryPackageImportDto?,
    ): RunManifestDto {
        val payload = withContext(Dispatchers.Default) {
            ImportRunPackageRequest(
                filename = filename,
                contentBase64 = clientBase64Encode(bytes),
                libraryPackage = libraryPackage,
            )
        }
        return repositoryRequest {
            val run = runManagement.import(payload)
            appPreferences.rememberRun(run.runId)
            run
        }
    }

    override suspend fun saveImportDefaults(characters: String, autoDistill: Boolean) {
        appPreferences.saveImportDefaults(characters, autoDistill)
    }

    override suspend fun getRun(runId: String): RunManifestDto = repositoryRequest {
        runManagement.get(runId)
    }

    override suspend fun deleteRun(runId: String): DeleteRunResponse = repositoryRequest {
        runManagement.delete(runId).also {
            appPreferences.forgetRun(runId)
        }
    }

    override suspend fun refreshRun(runId: String): RunManifestDto = repositoryRequest {
        runOps.refreshRun(runId)
    }

    override suspend fun stopRun(runId: String): RunManifestDto = repositoryRequest {
        runManagement.stop(runId)
    }

    override suspend fun redistill(runId: String, characters: List<String>): RunManifestDto = repositoryRequest {
        runOps.redistill(runId, RestartRunRequest(characters = characters))
    }

    override suspend fun createCrossoverSpace(
        title: String,
        worldSetting: String,
        participants: List<CrossoverParticipantRequest>,
    ): RunManifestDto = repositoryRequest {
        val run = runOps.createCrossoverSpace(CreateCrossoverSpaceRequest(title, worldSetting, participants))
        appPreferences.rememberRun(run.runId)
        run
    }

    override suspend fun resumeDistill(runId: String): RunManifestDto = repositoryRequest {
        runOps.resumeDistill(runId)
    }

    override suspend fun redistill(
        runId: String,
        characters: List<String>,
        novelName: String,
        novelBytes: ByteArray?,
        maxSentences: Int,
        maxChars: Int,
    ): RunManifestDto {
        val payload = withContext(Dispatchers.Default) {
            RestartRunRequest(
                characters = characters,
                novelName = novelName.takeIf { novelBytes != null }.orEmpty(),
                novelContentBase64 = novelBytes
                    ?.let { clientBase64Encode(it) }
                    .orEmpty(),
                maxSentences = maxSentences,
                maxChars = maxChars,
            )
        }
        return repositoryRequest { runOps.redistill(runId, payload) }
    }

    override suspend fun suggestRedistillSegments(
        runId: String,
        character: String,
        maxSegments: Int,
    ): RedistillSuggestionsDto = repositoryRequest {
        runOps.suggestRedistill(
            runId,
            SuggestRedistillSegmentsRequest(character = character, maxSegments = maxSegments),
        )
    }

    override suspend fun exportRun(
        runId: String,
        cacheDirectory: Path,
        includeDialogue: Boolean,
    ): ExportedRunPackage = repositoryRequest {
        // 书卷包由服务端直接生成 ByteArray（RunOperationsService.exportRunPackage），
        // 进程内不再有「HTTP 字节流 → 通道 → 临时文件」这条路，直接写盘即可。
        val (bytes, filename) = runOps.exportRun(runId, includeDialogue)
        val fileSystem = FileSystem.SYSTEM
        fileSystem.createDirectories(cacheDirectory)
        val file = cacheDirectory / "zaomeng-export-${clientMonotonicNanos()}.zip"
        fileSystem.write(file) { write(bytes) }
        ExportedRunPackage(
            filename = filename.ifBlank { "$runId.zaomeng-run.zip" },
            file = file,
            byteCount = bytes.size.toLong(),
        )
    }
}

class WorldMemoryRepositoryImpl(
    private val worldMemory: WorldMemoryClient,
) : WorldMemoryRepository {
    override suspend fun getWorldMemory(runId: String): WorldMemoryDto = repositoryRequest {
        worldMemory.get(runId)
    }

    override suspend fun saveWorldFact(
        runId: String,
        factId: String,
        requestBody: SaveWorldFactRequest,
    ): WorldFactDto = repositoryRequest {
        worldMemory.save(runId, factId, requestBody)
    }

    override suspend fun deleteWorldFact(runId: String, factId: String): DeleteStatusDto = repositoryRequest {
        worldMemory.delete(runId, factId)
    }
}

class RelationsRepositoryImpl(
    private val relations: RelationsClient,
) : RelationsRepository {
    override suspend fun getRelations(runId: String): RelationDetailsDto = repositoryRequest {
        relations.get(runId)
    }

    override suspend fun updateRelation(
        runId: String,
        relation: RelationItemDto,
    ): RelationDetailsDto = repositoryRequest {
        relations.update(
            runId,
            relation.pairKey,
            UpdateRelationDetailRequest(
                trust = relation.trust.coerceIn(0, 10),
                affection = relation.affection.coerceIn(0, 10),
                hostility = relation.hostility.coerceIn(0, 10),
                ambiguity = relation.ambiguity.coerceIn(0, 10),
                relationshipType = relation.relationshipType,
                relationChange = relation.relationChange,
                conflictPoint = relation.conflictPoint,
                typicalInteraction = relation.typicalInteraction,
            ),
        )
    }
}
