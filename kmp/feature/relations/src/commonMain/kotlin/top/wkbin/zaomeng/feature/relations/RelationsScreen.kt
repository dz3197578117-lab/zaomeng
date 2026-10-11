package top.wkbin.zaomeng.feature.relations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.api.RelationItemDto
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelationsScreen(
    viewModel: RelationsViewModel,
    onBack: () -> Unit,
    onOpenPersona: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var graphExpanded by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("人物关系校对") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::load, enabled = !state.loading && !state.refreshing) {
                        if (state.refreshing) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新关系")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> LoadingRelations(Modifier.padding(innerPadding))
            state.details == null -> MissingRelations(
                message = state.error.ifBlank { "暂时没有找到关系资料。" },
                onRetry = viewModel::load,
                modifier = Modifier.padding(innerPadding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    val details = requireNotNull(state.details)
                    RelationGraphSection(
                        items = details.items,
                        relationCount = details.relationCount,
                        conflictCount = details.conflictCount,
                        expanded = graphExpanded,
                        onExpandedChange = { graphExpanded = it },
                        onOpenPersona = onOpenPersona,
                    )
                }
                if (state.error.isNotBlank() || state.message.isNotBlank()) {
                    item {
                        Notice(
                            text = state.error.ifBlank { state.message },
                            isError = state.error.isNotBlank(),
                            onDismiss = viewModel::dismissNotice,
                        )
                    }
                }
                if (state.details?.items.isNullOrEmpty()) {
                    item {
                        Text(
                            "这份书卷还没有关系明细。完成关系抽取后会显示在这里。",
                            modifier = Modifier.padding(vertical = 32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(requireNotNull(state.details).items, key = RelationItemDto::pairKey) { item ->
                        RelationEditor(
                            item = item,
                            saving = state.savingPairKey == item.pairKey,
                            enabled = state.savingPairKey.isBlank(),
                            onChange = { change -> viewModel.updateItem(item.pairKey, change) },
                            onSave = { viewModel.save(item.pairKey) },
                        )
                    }
                }
            }
        }
    }
}

internal data class RelationGraphNode(
    val name: String,
    val x: Float,
    val y: Float,
)

internal data class RelationGraphEdge(
    val from: String,
    val to: String,
    val relation: RelationItemDto,
)

@Composable
private fun RelationGraphSection(
    items: List<RelationItemDto>,
    relationCount: Int,
    conflictCount: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenPersona: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("关系图谱", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "共 $relationCount 组关系${if (conflictCount > 0) "，$conflictCount 组需要留意" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onExpandedChange(!expanded) }) {
                    Text(if (expanded) "收起" else "查看图谱")
                }
            }
            if (expanded) {
                if (items.isEmpty()) {
                    Text("暂无可展示的关系数据。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    RelationGraph(items, onNodeClick = onOpenPersona)
                    Text(
                        "线条越粗代表关系越强烈；绿色=高信任，红色=高敌意，紫色=高情感。点击头像可进入该人物的档案校对。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RelationGraph(items: List<RelationItemDto>, onNodeClick: (String) -> Unit) {
    val nodes = remember(items) { relationGraphNodes(items) }
    val colors = MaterialTheme.colorScheme
    val edges = remember(items, nodes) { relationGraphEdges(items, nodes) }
    val edgeStyles = edges.map { edge -> edge to relationEdgeColor(edge.relation, colors) }
    val labelled = remember(edges) {
        edges.sortedByDescending { relationStrength(it.relation) }
            .take(if (edges.size <= 6) 6 else 4)
            .filter { it.relation.relationshipType.isNotBlank() }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val positions = nodes.associate { node ->
                node.name to Offset(size.width * node.x, size.height * node.y)
            }
            val radius = 18.dp.toPx()
            edgeStyles.forEach { (edge, color) ->
                val start = positions[edge.from] ?: return@forEach
                val end = positions[edge.to] ?: return@forEach
                val delta = end - start
                val length = delta.getDistance()
                if (length <= radius * 2f) return@forEach
                val direction = delta / length
                val strength = relationStrength(edge.relation)
                drawLine(
                    color = color.copy(alpha = 0.5f + 0.45f * strength),
                    start = start + direction * radius,
                    end = end - direction * radius,
                    strokeWidth = (1.2f + 3.8f * strength) * density,
                )
            }
        }
        labelled.forEach { edge ->
            val from = nodes.firstOrNull { it.name == edge.from } ?: return@forEach
            val to = nodes.firstOrNull { it.name == edge.to } ?: return@forEach
            val midX = (from.x + to.x) / 2f
            val midY = (from.y + to.y) / 2f
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = maxWidth * midX - 42.dp, y = 380.dp * midY - 10.dp)
                    .width(84.dp),
                color = colors.surfaceContainerHighest,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    edge.relation.relationshipType,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        nodes.forEach { node ->
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = maxWidth * node.x - 40.dp, y = 380.dp * node.y - 20.dp)
                    .width(80.dp)
                    .clickable { onNodeClick(node.name) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        node.name.take(1),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    node.name,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun relationEdgeColor(relation: RelationItemDto, colors: ColorScheme): Color = when {
    relation.hostility >= 6 -> colors.error
    relation.affection >= 7 -> colors.tertiary
    relation.trust >= 7 -> colors.primary
    else -> colors.outline
}

/** 关系强度归一化到 0..1，用于线条粗细与透明度。 */
internal fun relationStrength(relation: RelationItemDto): Float =
    maxOf(relation.trust, relation.affection, relation.hostility, relation.ambiguity)
        .coerceIn(0, 10) / 10f

internal fun relationGraphEdges(
    items: List<RelationItemDto>,
    nodes: List<RelationGraphNode>,
): List<RelationGraphEdge> {
    val known = nodes.map(RelationGraphNode::name).toSet()
    return items.mapNotNull { relation ->
        val pair = relation.characters.take(2).map(String::trim)
        val from = pair.getOrNull(0)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val to = pair.getOrNull(1)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        if (from == to || from !in known || to !in known) return@mapNotNull null
        RelationGraphEdge(from, to, relation)
    }
}

/**
 * 力导向布局：节点互斥、有关系的节点互相吸引，再叠加轻微向心力。
 *
 * 相比固定圆周布局，可以把关系紧密的人物聚成一团，关系稀疏的人物推到外圈，
 * 结构一眼可辨。完全确定性（无随机)，因此 remember 后不会抖动。
 */
internal fun relationGraphNodes(items: List<RelationItemDto>): List<RelationGraphNode> {
    val names = items.flatMap { it.characters.take(2) }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    if (names.isEmpty()) return emptyList()
    if (names.size == 1) return listOf(RelationGraphNode(names[0], 0.5f, 0.5f))
    if (names.size > 40) {
        return names.mapIndexed { index, name ->
            val angle = (2.0 * PI * index / names.size) - PI / 2.0
            RelationGraphNode(name, (0.5 + 0.36 * cos(angle)).toFloat(), (0.5 + 0.38 * sin(angle)).toFloat())
        }
    }

    val count = names.size
    val index = names.withIndex().associate { (i, name) -> name to i }
    val px = FloatArray(count)
    val py = FloatArray(count)
    names.forEachIndexed { i, _ ->
        val angle = (2.0 * PI * i / count) - PI / 2.0
        px[i] = (0.5 + 0.34 * cos(angle)).toFloat()
        py[i] = (0.5 + 0.37 * sin(angle)).toFloat()
    }
    val edges = items.mapNotNull { relation ->
        val pair = relation.characters.take(2).map(String::trim)
        val a = pair.getOrNull(0)?.let(index::get)
        val b = pair.getOrNull(1)?.let(index::get)
        if (a == null || b == null || a == b) null else a to b
    }

    val iterations = 240
    val idealLength = 0.36f
    val dx = FloatArray(count)
    val dy = FloatArray(count)
    for (step in 0 until iterations) {
        dx.fill(0f)
        dy.fill(0f)
        for (i in 0 until count) {
            for (j in i + 1 until count) {
                var vx = px[i] - px[j]
                var vy = py[i] - py[j]
                var distSq = vx * vx + vy * vy
                if (distSq < 1e-5f) {
                    vx = 0.001f * (i + 1)
                    vy = 0.001f * (j + 1)
                    distSq = vx * vx + vy * vy
                }
                val dist = sqrt(distSq)
                val force = 0.0018f / distSq
                val fx = vx / dist * force
                val fy = vy / dist * force
                dx[i] += fx; dy[i] += fy
                dx[j] -= fx; dy[j] -= fy
            }
        }
        edges.forEach { (a, b) ->
            val vx = px[b] - px[a]
            val vy = py[b] - py[a]
            val dist = max(sqrt(vx * vx + vy * vy), 1e-4f)
            val force = (dist - idealLength) * 0.06f
            val fx = vx / dist * force
            val fy = vy / dist * force
            dx[a] += fx; dy[a] += fy
            dx[b] -= fx; dy[b] -= fy
        }
        val cooling = 1f - step.toFloat() / iterations
        for (i in 0 until count) {
            dx[i] += (0.5f - px[i]) * 0.02f
            dy[i] += (0.5f - py[i]) * 0.02f
            px[i] = (px[i] + dx[i] * cooling).coerceIn(0.09f, 0.91f)
            py[i] = (py[i] + dy[i] * cooling).coerceIn(0.10f, 0.90f)
        }
    }
    return names.mapIndexed { i, name -> RelationGraphNode(name, px[i], py[i]) }
}

@Composable
private fun RelationEditor(
    item: RelationItemDto,
    saving: Boolean,
    enabled: Boolean,
    onChange: ((RelationItemDto) -> RelationItemDto) -> Unit,
    onSave: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.characters.joinToString("、").ifBlank { item.pairKey },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (item.relationshipType.isNotBlank()) {
                        Text(item.relationshipType, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Button(onClick = onSave, enabled = enabled) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Save, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("保存")
                }
            }

            RelationMetric("信任", item.trust, enabled) { value ->
                onChange { it.copy(trust = value) }
            }
            RelationMetric("情感", item.affection, enabled) { value ->
                onChange { it.copy(affection = value) }
            }
            RelationMetric("敌意", item.hostility, enabled) { value ->
                onChange { it.copy(hostility = value) }
            }
            RelationMetric("暧昧", item.ambiguity, enabled) { value ->
                onChange { it.copy(ambiguity = value) }
            }

            RelationField("关系类型", item.relationshipType, enabled) { value ->
                onChange { it.copy(relationshipType = value) }
            }
            RelationField("互动摘要", item.typicalInteraction, enabled) { value ->
                onChange { it.copy(typicalInteraction = value) }
            }
            RelationField("冲突点", item.conflictPoint, enabled) { value ->
                onChange { it.copy(conflictPoint = value) }
            }
            RelationField("关系变化", item.relationChange, enabled) { value ->
                onChange { it.copy(relationChange = value) }
            }

            if (item.evidenceLines.isNotEmpty()) {
                Text("证据句", style = MaterialTheme.typography.labelLarge)
                item.evidenceLines.take(6).forEach { line ->
                    Text("• $line", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RelationMetric(label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value.toString(), fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(0, 10)) },
            valueRange = 0f..10f,
            steps = 9,
            enabled = enabled,
        )
    }
}

@Composable
private fun RelationField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        minLines = if (label == "关系类型") 1 else 2,
        maxLines = if (label == "关系类型") 1 else 4,
    )
}

@Composable
private fun Notice(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun LoadingRelations(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun MissingRelations(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text("重试") }
        }
    }
}
