package com.pict.metatool.ui.preset

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pict.metatool.core.result.PictResult
import com.pict.metatool.data.preset.MergedPresetCatalog
import com.pict.metatool.domain.model.FieldCatalog
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.preset.FieldRule
import com.pict.metatool.domain.preset.Preset
import com.pict.metatool.domain.preset.PresetConstraint
import com.pict.metatool.domain.preset.PresetKind
import com.pict.metatool.domain.preset.PresetOrigin
import com.pict.metatool.domain.preset.PresetValue
import com.pict.metatool.domain.preset.UserFieldInput
import com.pict.metatool.domain.preset.UserPresetInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 字段清单里的一行：中文名 + TagKey + 规则摘要。 */
data class PresetFieldRow(val key: TagKey, val label: String, val rule: String)

/** 展开一份预设时看到的细节。 */
data class PresetDetail(
    val id: String,
    val origin: PresetOrigin,
    val description: String?,
    val author: String?,
    val license: String?,
    val tags: List<String>,
    val fields: List<PresetFieldRow>,
    val constraints: List<String>,
)

/** 按类别分好的一组预设：自建在前、内置在后（跟预设弹层的显示口径一致）。 */
data class PresetKindGroup(val kind: PresetKind, val presets: List<Preset>)

/** 删完 / 存完给界面看的一次性回执。文案由界面出，ViewModel 不碰 Android 资源。 */
sealed interface PresetManageEvent {

    data class Deleted(val name: String) : PresetManageEvent

    data class DeleteFailed(val name: String) : PresetManageEvent

    /**
     * 存下来了。管理页这边**不用像编辑页那样顺手选中**——加到哪一类，这一页上
     * 那一类就排在自建那一段的开头，回执里报个名字够用了。
     */
    data class Saved(val name: String, val isNew: Boolean) : PresetManageEvent
}

data class PresetManageState(
    val groups: List<PresetKindGroup> = emptyList(),
    val builtinCount: Int = 0,
    val userCount: Int = 0,
    val builtinIssues: List<String> = emptyList(),
    val userIssues: List<String> = emptyList(),
    val expanded: PresetDetail? = null,
    val pendingDelete: Preset? = null,
    /** 正在编的表单（加一份新的、或改一份自建的）；null = 弹层没开。 */
    val editor: UserPresetInput? = null,
    /** 存不下来的原因，摆在弹层里——关掉弹层等于把用户刚填的丢了。 */
    val editorMessage: String? = null,
    val event: PresetManageEvent? = null,
)

/**
 * 预设管理页（设置 → 预设管理）。
 *
 * 一页看全内置 + 自建的预设：点一行展开字段清单，**加一份自己的 / 改自建 / 删自建**都在这页上。
 * 写（[PresetManageViewModel.saveEditor]、[PresetManageViewModel.askDelete]）只对自建开放——内置那几份钉在
 * 安装包里（`BuiltinPresetCoverageTest` 保证它们都在），改也只会改出本地一份，说不清算谁的。
 * 表单本身是编辑页、批量页那份「自建预设」弹层（`UserPresetEditorSheet`），三个入口同一张表。
 *
 * 读取是同步的：跟编辑页、批量页打开预设弹层走的是同一份数据、同一条路径，
 * 一份目录 + 几十个小 JSON，没有另开线程的必要。
 */
class PresetManageViewModel(private val catalog: MergedPresetCatalog) : ViewModel() {

    private val _state = MutableStateFlow(PresetManageState())
    val state: StateFlow<PresetManageState> = _state.asStateFlow()

    init {
        reload()
    }

    /**
     * 重读目录：进页面、删完之后都走它。
     *
     * 顺手 [MergedPresetCatalog.refresh] 丢掉用户目录的读取缓存，这样用户在文件管理器里
     * 手工塞进来（或删掉）的预设，回到这一页就能看见。
     */
    fun reload() {
        catalog.refresh()
        val presets = catalog.all()
        val expandedId = _state.value.expanded?.id
        _state.value = _state.value.copy(
            groups = group(presets),
            builtinCount = presets.count { it.origin != PresetOrigin.USER },
            userCount = presets.count { it.origin == PresetOrigin.USER },
            builtinIssues = catalog.issues.map { it.toString() },
            userIssues = catalog.userIssues.map { it.toString() },
            expanded = expandedId?.let { id -> presets.firstOrNull { it.id == id } }?.let(::detailOf),
            pendingDelete = null,
        )
    }

    /** 点一下：收起已展开的那份，或展开这一份。 */
    fun toggle(preset: Preset) {
        val current = _state.value.expanded?.id
        _state.value = _state.value.copy(
            expanded = if (current == preset.id) null else detailOf(preset),
        )
    }

    /** 只有自建能删：内置的连确认框都不该弹。 */
    fun askDelete(preset: Preset) {
        if (preset.origin != PresetOrigin.USER) return
        _state.value = _state.value.copy(pendingDelete = preset)
    }

    fun dismissDelete() {
        _state.value = _state.value.copy(pendingDelete = null)
    }

    /** 删掉待删的那份；删不动也照实回执，不假装成功。 */
    fun confirmDelete() {
        val target = _state.value.pendingDelete ?: return
        val removed = catalog.delete(target.id)
        reload()
        _state.value = _state.value.copy(
            event = if (removed) {
                PresetManageEvent.Deleted(target.name)
            } else {
                PresetManageEvent.DeleteFailed(target.name)
            },
        )
    }

    /** 回执只该弹一次。 */
    fun consumeEvent() {
        _state.value = _state.value.copy(event = null)
    }

    /**
     * 「加一份自己的」：开一张空表，默认归「设备」那一类——内置里最多的就是机型。
     * 类别在弹层里能改，这里不用先问一遍。
     */
    fun addPreset(kind: PresetKind = PresetKind.DEVICE) {
        _state.value = _state.value.copy(
            editor = UserPresetInput(kind = kind, rows = listOf(UserFieldInput(null, ""))),
            editorMessage = null,
        )
    }

    /**
     * 改一份自建的：把盘上那份填回表单。`UserPresetInput.from` 会把表单认不出来的规则
     * 原样收进 `preserved`，改完存回去不会把原来那几十个字段弄丢。
     *
     * 内置的不给改，跟 [askDelete] 同一个口径：安装包里那几份改完也只是本地多一份，
     * 说不清算谁的。
     */
    fun editPreset(preset: Preset) {
        if (preset.origin != PresetOrigin.USER) return
        _state.value = _state.value.copy(
            editor = UserPresetInput.from(preset),
            editorMessage = null,
        )
    }

    fun dismissEditor() {
        _state.value = _state.value.copy(editor = null, editorMessage = null)
    }

    /**
     * 存一份自建预设：走 [MergedPresetCatalog.save]（它落盘前会用 `PresetParser` 读回一遍）。
     *
     * 存不下来就**留在弹层里**把原因摆在上面，不关弹层——关掉等于把用户刚填的那些丢了；
     * 存下来了才重读目录、关弹层、给回执。
     */
    fun saveEditor(input: UserPresetInput) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { catalog.save(input) }
            applySaveResult(saved, input.isNew)
        }
    }

    /**
     * 把存盘结果落到状态上。单独抽出来是给测试留一条缝：真跑 [viewModelScope] 那条路要先有
     * `Dispatchers.Main`（只有设备上才有），单位测试里够不着；这里测的是同一段逻辑。
     */
    internal fun applySaveResult(saved: PictResult<Preset>, wasNew: Boolean) {
        when (saved) {
            is PictResult.Failure -> _state.value = _state.value.copy(
                editorMessage = "存不下来：${saved.failure.detail ?: saved.code}",
            )

            is PictResult.Success -> {
                reload()
                _state.value = _state.value.copy(
                    editor = null,
                    editorMessage = null,
                    event = PresetManageEvent.Saved(saved.value.name, wasNew),
                )
            }
        }
    }

    /** 弹层里那条「删掉这份」：先收弹层，再走这一页统一的确认框，免得两条删除路径。 */
    fun askDeleteById(presetId: String) {
        val target = _state.value.groups.asSequence()
            .flatMap { it.presets.asSequence() }
            .firstOrNull { it.id == presetId }
        _state.value = _state.value.copy(editor = null, editorMessage = null)
        if (target != null) askDelete(target)
    }

    private fun group(presets: List<Preset>): List<PresetKindGroup> =
        PresetKind.entries.mapNotNull { kind ->
            val inKind = presets.filter { it.kind == kind }
            if (inKind.isEmpty()) {
                null
            } else {
                val (mine, packaged) = inKind.partition { it.origin == PresetOrigin.USER }
                PresetKindGroup(kind, mine.sortedBy { it.id } + packaged.sortedBy { it.id })
            }
        }

    private fun detailOf(preset: Preset): PresetDetail = PresetDetail(
        id = preset.id,
        origin = preset.origin,
        description = preset.description,
        author = preset.author,
        license = preset.license,
        tags = preset.tags,
        // 按字段目录的顺序排：跟编辑页的字段列表同一套次序，展开两份预设才能对得上号。
        fields = preset.fields.entries
            .sortedWith(compareBy({ FieldCatalog.order(it.key) }, { it.key.full }))
            .map { (key, rule) ->
                PresetFieldRow(
                    key = key,
                    label = FieldCatalog.spec(key)?.label ?: key.full,
                    rule = ruleSummary(rule),
                )
            },
        constraints = preset.constraints.map(::constraintSummary),
    )

    companion object {

        /** 规则摘要：模式名 + 该模式的关键参数（值本身可能是一整池，摘要里只给规模）。 */
        fun ruleSummary(rule: FieldRule): String = when (rule) {
            is FieldRule.Fixed -> "${rule.mode.label}：${valueText(rule.value)}"
            is FieldRule.Pool -> "${rule.mode.label}（${rule.candidates.size} 项）"
            is FieldRule.Range -> "${rule.mode.label} ${trim(rule.min)}~${trim(rule.max)}"
            is FieldRule.DateTimeRule ->
                "${rule.mode.label} ${rule.start.toLocalDate()}~${rule.end.toLocalDate()}"
            is FieldRule.Gps -> "${rule.mode.label}（半径 ${trim(rule.radiusMeters)} 米）"
            is FieldRule.FromSource -> "${rule.mode.label}（取自 ${rule.sourceKey.full}）"
            is FieldRule.Jitter -> rule.radiusMeters?.let { meters ->
                "${rule.mode.label}（半径 ${trim(meters)} 米）"
            } ?: "${rule.mode.label}（±${trim(rule.percent * 100)}%）"
        }

        /** 约束摘要：类型 + 涉及的字段。 */
        fun constraintSummary(constraint: PresetConstraint): String {
            val (label, keys) = when (constraint) {
                is PresetConstraint.DatetimeOrder -> "时间先后" to constraint.keys
                is PresetConstraint.Mutex -> "互斥" to constraint.keys
                is PresetConstraint.Requires -> "成组出现" to constraint.keys
                is PresetConstraint.Clamp -> "随机型收敛" to constraint.keys
            }
            return "$label：" + keys.joinToString("、") { it.full }
        }

        private fun valueText(value: PresetValue): String = when (value) {
            is PresetValue.Text -> value.text
            is PresetValue.Number -> value.literal
            PresetValue.Null -> "空"
        }

        /** `1.0` 写成 `1`：摘要里不该出现「半径 500.0 米」。 */
        private fun trim(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { PresetManageViewModel(MergedPresetCatalog.of(context.applicationContext)) }
        }
    }
}
