@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.taxledger.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.taxledger.data.AppTab
import com.example.taxledger.data.AttachmentFormat
import com.example.taxledger.data.Invoice
import com.example.taxledger.data.InvoiceBreakdown
import com.example.taxledger.data.InvoiceDraft
import com.example.taxledger.data.LedgerRepository
import com.example.taxledger.data.LedgerSnapshot
import com.example.taxledger.data.LedgerUiState
import com.example.taxledger.data.Person
import com.example.taxledger.data.QuarterPersonSummary
import com.example.taxledger.data.QuarterTotals
import com.example.taxledger.data.TaxLine
import com.example.taxledger.data.TaxSettings
import com.example.taxledger.data.buildQuarterExport
import com.example.taxledger.data.buildQuarterPersonSummary
import com.example.taxledger.data.buildQuarterTotals
import com.example.taxledger.data.formatDate
import com.example.taxledger.data.invoiceBreakdown
import com.example.taxledger.data.quarterLabel
import com.example.taxledger.data.quarterOf
import com.example.taxledger.data.toMoneyOrNull
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private val DatePattern = DateTimeFormatter.ofPattern("yyyy-MM-dd")

class LedgerViewModel(private val repository: LedgerRepository) : ViewModel() {
    private val seedPeople = listOf(
        Person(displayName = "企业A", defaultInvoiceTaxRatePercent = 1),
        Person(displayName = "企业B", defaultInvoiceTaxRatePercent = 3),
    )

    private val _state = MutableStateFlow(repository.load()?.toUiState(seedPeople) ?: defaultState(seedPeople))
    val state: StateFlow<LedgerUiState> = _state.asStateFlow()

    fun setTab(tab: AppTab) = update { it.copy(activeTab = tab) }
    fun setQuarter(year: Int, quarter: Int) = update { it.copy(selectedYear = year, selectedQuarter = quarter) }
    fun openAddPersonDialog() = update { it.copy(showAddPersonDialog = true, pendingPersonName = "") }
    fun dismissAddPersonDialog() = update { it.copy(showAddPersonDialog = false, pendingPersonName = "") }
    fun updatePendingPersonName(value: String) = update { it.copy(pendingPersonName = value) }
    fun consumeStatusMessage() = update { it.copy(statusMessage = null) }
    fun updateTaxSettings(block: (TaxSettings) -> TaxSettings) = update { it.copy(taxSettings = block(it.taxSettings)) }
    fun updateDraft(block: (InvoiceDraft) -> InvoiceDraft) = update { it.copy(draft = block(it.draft), statusMessage = null) }

    fun addPerson() {
        val name = state.value.pendingPersonName.trim()
        if (name.isBlank()) return
        update {
            val person = Person(displayName = name)
            it.copy(
                people = it.people + person,
                draft = it.draft.copy(personId = it.draft.personId.ifBlank { person.id }),
                showAddPersonDialog = false,
                pendingPersonName = "",
                statusMessage = "已添加人员：$name",
            )
        }
    }

    fun togglePersonEnabled(personId: String) {
        update {
            it.copy(people = it.people.map { person -> if (person.id == personId) person.copy(isEnabled = !person.isEnabled) else person })
        }
    }

    fun updatePersonDefaultRate(personId: String, ratePercent: Int) {
        update {
            it.copy(people = it.people.map { person -> if (person.id == personId) person.copy(defaultInvoiceTaxRatePercent = ratePercent) else person })
        }
    }

    fun importInvoice(uri: Uri, repository: LedgerRepository) {
        runCatching {
            val imported = repository.parseImportedInvoice(uri)
            update {
                it.copy(
                    draft = it.draft.copy(
                        grossAmount = imported.grossAmount,
                        issuedOn = imported.issuedOn,
                        invoiceNumber = imported.invoiceNumber,
                        attachmentName = imported.attachmentName,
                        attachmentPath = imported.attachmentPath,
                        sourceFormat = imported.format,
                    ),
                    statusMessage = "已识别并回填发票内容",
                )
            }
        }.onFailure { error ->
            update { ui -> ui.copy(statusMessage = "导入失败：${error.message ?: "未知错误"}") }
        }
    }

    fun saveInvoice() {
        val current = state.value
        val gross = current.draft.grossAmount.toMoneyOrNull() ?: return update { it.copy(statusMessage = "请输入正确的含税金额") }
        if (current.draft.personId.isBlank()) return update { it.copy(statusMessage = "请选择所属人员") }

        val invoice = Invoice(
            id = UUID.randomUUID().toString(),
            personId = current.draft.personId,
            grossAmount = gross.toPlainString(),
            invoiceTaxRatePercent = current.draft.invoiceTaxRatePercent,
            issuedOn = current.draft.issuedOn,
            attachmentName = current.draft.attachmentName,
            attachmentPath = current.draft.attachmentPath,
            sourceFormat = current.draft.sourceFormat,
            note = current.draft.note,
            invoiceNumber = current.draft.invoiceNumber,
        )

        val nextRate = current.people.firstOrNull { it.id == invoice.personId }?.defaultInvoiceTaxRatePercent ?: 1
        update {
            it.copy(
                invoices = it.invoices + invoice,
                draft = InvoiceDraft(
                    personId = invoice.personId,
                    issuedOn = LocalDate.now(),
                    invoiceTaxRatePercent = nextRate,
                ),
                statusMessage = "已保存发票",
            )
        }
    }

    fun exportQuarter(repository: LedgerRepository): List<File> {
        val current = state.value
        val bundle = buildQuarterExport(current.selectedYear, current.selectedQuarter, current.people, current.invoices, current.taxSettings)
        val files = repository.exportQuarter(current.selectedYear, current.selectedQuarter, bundle)
        update { it.copy(statusMessage = "已导出季度报表") }
        return files
    }

    private fun update(transform: (LedgerUiState) -> LedgerUiState) {
        _state.value = transform(_state.value)
        persist()
    }

    private fun persist() {
        val state = _state.value
        repository.save(
            LedgerSnapshot(
                people = state.people,
                invoices = state.invoices,
                taxSettings = state.taxSettings,
                selectedYear = state.selectedYear,
                selectedQuarter = state.selectedQuarter,
                activeTab = state.activeTab,
            ),
        )
    }
}

@Composable
fun TaxLedgerApp() {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { LedgerRepository(context) }
    val factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(repository) as T
        }
    }
    val viewModel: LedgerViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    val tabs = AppTab.entries

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 840.dp
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (useRail) {
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail {
                        Spacer(Modifier.height(12.dp))
                        tabs.forEach { tab ->
                            NavigationRailItem(selected = state.activeTab == tab, onClick = { viewModel.setTab(tab) }, icon = { Icon(tab.icon(), contentDescription = tab.title) }, label = { Text(tab.title) })
                        }
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AppTopBar(state, viewModel, repository)
                        AppContent(state, viewModel, repository)
                    }
                }
            } else {
                Scaffold(
                    topBar = { AppTopBar(state, viewModel, repository) },
                    bottomBar = {
                        NavigationBar {
                            tabs.forEach { tab ->
                                NavigationBarItem(selected = state.activeTab == tab, onClick = { viewModel.setTab(tab) }, icon = { Icon(tab.icon(), contentDescription = tab.title) }, label = { Text(tab.title) })
                            }
                        }
                    },
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        AppContent(state, viewModel, repository)
                    }
                }
            }
        }
    }

    if (state.showAddPersonDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAddPersonDialog,
            confirmButton = { TextButton(onClick = viewModel::addPerson) { Text("添加") } },
            dismissButton = { TextButton(onClick = viewModel::dismissAddPersonDialog) { Text("取消") } },
            title = { Text("新增人员") },
            text = {
                OutlinedTextField(
                    value = state.pendingPersonName,
                    onValueChange = viewModel::updatePendingPersonName,
                    singleLine = true,
                    label = { Text("名称") },
                )
            },
        )
    }
}

@Composable
private fun AppTopBar(state: LedgerUiState, viewModel: LedgerViewModel, repository: LedgerRepository) {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val files = viewModel.exportQuarter(repository)
            val exportedText = files.joinToString("\n") { it.absolutePath }
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(exportedText.toByteArray())
            }
        }
    }

    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("税费记账", fontWeight = FontWeight.SemiBold)
                Text("${quarterLabel(state.selectedYear, state.selectedQuarter)} · 本地账本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        actions = {
            IconButton(onClick = { exportLauncher.launch("quarter_report.txt") }) {
                Icon(Icons.Default.Download, contentDescription = "导出")
            }
            IconButton(onClick = { viewModel.setTab(AppTab.Settings) }) {
                Icon(Icons.Default.FilterAlt, contentDescription = "设置")
            }
        },
    )
}

@Composable
private fun AppContent(state: LedgerUiState, viewModel: LedgerViewModel, repository: LedgerRepository) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.statusMessage?.let {
            AssistChip(onClick = viewModel::consumeStatusMessage, label = { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) })
        }

        when (state.activeTab) {
            AppTab.Overview -> OverviewTab(state)
            AppTab.Entry -> EntryTab(state, viewModel, repository)
            AppTab.Quarter -> QuarterTab(state, viewModel)
            AppTab.People -> PeopleTab(state, viewModel)
            AppTab.Settings -> SettingsTab(state, viewModel)
        }
    }
}

@Composable
private fun OverviewTab(state: LedgerUiState) {
    val quarterInvoices = state.currentQuarterInvoices()
    val totals = buildQuarterTotals(quarterInvoices, state.taxSettings)
    val summaries = state.people.filter { it.isEnabled }.map {
        buildQuarterPersonSummary(it, quarterInvoices.filter { invoice -> invoice.personId == it.id }, state.taxSettings)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SummaryHero(state, totals)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("本季发票", quarterInvoices.size.toString(), Icons.Default.Description)
            StatCard("本季税费", "¥${totals.totalPayable.format2()}", Icons.Default.Calculate)
            StatCard("不含税额", "¥${totals.taxableAmount.format2()}", Icons.Default.Storage)
        }
        SectionTitle("人员概览")
        summaries.forEach { PersonSummaryCard(it) }
        SectionTitle("最近发票")
        state.invoices.takeLast(6).asReversed().forEach { invoice ->
            InvoiceRow(invoice, state.people.firstOrNull { it.id == invoice.personId }?.displayName ?: "未命名")
        }
    }
}

@Composable
private fun EntryTab(state: LedgerUiState, viewModel: LedgerViewModel, repository: LedgerRepository) {
    val draft = state.draft
    val preview = draft.toInvoicePreview(state)
    val enabledPeople = state.people.filter { it.isEnabled }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importInvoice(it, repository) } }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importInvoice(it, repository) } }
    val ofdPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importInvoice(it, repository) } }

    if (draft.personId.isBlank() && enabledPeople.isNotEmpty()) {
        viewModel.updateDraft {
            it.copy(personId = enabledPeople.first().id, invoiceTaxRatePercent = enabledPeople.first().defaultInvoiceTaxRatePercent)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("新增发票")
        OutlinedCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PersonPicker(
                    people = enabledPeople,
                    selectedPersonId = draft.personId,
                    onSelected = { person -> viewModel.updateDraft { it.copy(personId = person.id, invoiceTaxRatePercent = person.defaultInvoiceTaxRatePercent) } },
                    onAddPerson = viewModel::openAddPersonDialog,
                )

                OutlinedTextField(value = draft.grossAmount, onValueChange = { value -> viewModel.updateDraft { it.copy(grossAmount = value) } }, modifier = Modifier.fillMaxWidth(), label = { Text("含税金额") }, prefix = { Text("¥") }, singleLine = true)
                OutlinedTextField(value = draft.invoiceNumber, onValueChange = { value -> viewModel.updateDraft { it.copy(invoiceNumber = value) } }, modifier = Modifier.fillMaxWidth(), label = { Text("发票号码") }, singleLine = true)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("发票税率", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf(1, 3).forEachIndexed { index, rate ->
                            SegmentedButton(selected = draft.invoiceTaxRatePercent == rate, onClick = { viewModel.updateDraft { it.copy(invoiceTaxRatePercent = rate) } }, shape = SegmentedButtonDefaults.itemShape(index = index, count = 2)) { Text("$rate%") }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { viewModel.updateDraft { it.copy(issuedOn = LocalDate.now()) } }, label = { Text(formatDate(draft.issuedOn)) }, leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) })
                    AssistChip(onClick = {}, label = { Text(draft.sourceFormat?.label ?: "导入文件") }, leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) })
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportButton("PDF", Icons.Default.PictureAsPdf) { pdfPicker.launch(arrayOf("application/pdf")) }
                    ImportButton("图片", Icons.Default.UploadFile) { imagePicker.launch(arrayOf("image/*")) }
                    ImportButton("OFD", Icons.Default.Description) { ofdPicker.launch(arrayOf("*/*")) }
                }

                OutlinedTextField(value = draft.note, onValueChange = { value -> viewModel.updateDraft { it.copy(note = value) } }, modifier = Modifier.fillMaxWidth(), label = { Text("备注") }, minLines = 2)

                BreakdownPreview(preview)

                Button(onClick = viewModel::saveInvoice, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("保存发票")
                }
            }
        }
    }
}

@Composable
private fun QuarterTab(state: LedgerUiState, viewModel: LedgerViewModel) {
    val quarterInvoices = state.currentQuarterInvoices()
    val totals = buildQuarterTotals(quarterInvoices, state.taxSettings)
    val summaries = state.people.filter { it.isEnabled }.map {
        buildQuarterPersonSummary(it, quarterInvoices.filter { invoice -> invoice.personId == it.id }, state.taxSettings)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("季度汇总")
        QuarterSelector(state.selectedYear, state.selectedQuarter, viewModel::setQuarter)
        SummaryHero(state, totals)
        SectionTitle("税项明细")
        TaxLineSection(state, totals)
        SectionTitle("人员分摊")
        summaries.forEach { PersonSummaryCard(it) }
    }
}

@Composable
private fun PeopleTab(state: LedgerUiState, viewModel: LedgerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("人员管理")
        OutlinedButton(onClick = viewModel::openAddPersonDialog, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("新增人员")
        }
        state.people.forEach { person ->
            ElevatedCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(person.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (person.isEnabled) "启用中" else "已停用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilterChip(selected = person.isEnabled, onClick = { viewModel.togglePersonEnabled(person.id) }, label = { Text(if (person.isEnabled) "停用" else "启用") })
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("默认税率")
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            listOf(1, 3).forEachIndexed { index, rate ->
                                SegmentedButton(selected = person.defaultInvoiceTaxRatePercent == rate, onClick = { viewModel.updatePersonDefaultRate(person.id, rate) }, shape = SegmentedButtonDefaults.itemShape(index = index, count = 2)) { Text("$rate%") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(state: LedgerUiState, viewModel: LedgerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("税务参数")
        ElevatedCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("城建税税率")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(1, 5, 7).forEachIndexed { index, rate ->
                        SegmentedButton(selected = state.taxSettings.cityConstructionTaxRatePercent == rate, onClick = { viewModel.updateTaxSettings { it.copy(cityConstructionTaxRatePercent = rate) } }, shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)) { Text("$rate%") }
                    }
                }
                Text("教育费附加和地方教育附加按你提供的规则计算；季度阈值默认30万元。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SummaryHero(state: LedgerUiState, totals: QuarterTotals) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("当前季度", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(quarterLabel(state.selectedYear, state.selectedQuarter), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricBlock("含税总额", "¥${totals.grossAmount.format2()}")
                MetricBlock("不含税额", "¥${totals.taxableAmount.format2()}")
                MetricBlock("应缴税费", "¥${totals.totalPayable.format2()}")
            }
            if (totals.taxableThresholdReached) {
                AssistChip(onClick = {}, label = { Text("季度销售额已超过30万元") }, leadingIcon = { Icon(Icons.Default.WarningAmber, contentDescription = null) })
            }
        }
    }
}

@Composable
private fun TaxLineSection(state: LedgerUiState, totals: QuarterTotals) {
    val city = TaxLine("城市维护建设税", totals.vat, state.taxSettings.cityConstructionTaxRatePercent, totals.cityTax / 0.5, totals.cityTax, 50, totals.cityTax, totals.cityTax)
    val education = TaxLine("教育费附加", totals.vat, 3, totals.educationFee, if (totals.educationFee == 0.0) totals.educationFee else 0.0, if (totals.educationFee == 0.0) 100 else 0, if (totals.educationFee == 0.0) totals.educationFee else 0.0, totals.educationFee, totals.educationFee == 0.0)
    val local = TaxLine("地方教育附加", totals.vat, 2, totals.localEducationFee, if (totals.localEducationFee == 0.0) totals.localEducationFee else 0.0, if (totals.localEducationFee == 0.0) 100 else 0, if (totals.localEducationFee == 0.0) totals.localEducationFee else 0.0, totals.localEducationFee, totals.localEducationFee == 0.0)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(city, education, local).forEach { TaxLineCard(it) }
    }
}

@Composable
private fun TaxLineCard(line: TaxLine) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(line.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("¥${line.finalPayable.format2()}", fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("计税依据 ¥${line.taxBase.format2()}") })
                AssistChip(onClick = {}, label = { Text("税率 ${line.ratePercent}%") })
                AssistChip(onClick = {}, label = { Text("减征 ${line.reductionPercent}%") })
            }
            Text(if (line.isExempt) "本期减免后实缴为 0.00" else "本期应补缴金额按规则计算", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PersonSummaryCard(summary: QuarterPersonSummary) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(summary.personName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${summary.invoiceCount} 张发票", style = MaterialTheme.typography.labelMedium)
                }
                Text("¥${summary.totalPayable.format2()}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                AssistChip(onClick = {}, label = { Text("含税 ¥${summary.grossAmount.format2()}") })
                AssistChip(onClick = {}, label = { Text("不含税 ¥${summary.taxableAmount.format2()}") })
                AssistChip(onClick = {}, label = { Text("税费 ¥${summary.vat.format2()}") })
            }
        }
    }
}

@Composable
private fun InvoiceRow(invoice: Invoice, personName: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(personName, fontWeight = FontWeight.SemiBold)
                Text("¥${invoice.grossAmount.toMoneyOrNull()?.format2() ?: invoice.grossAmount}")
            }
            Text("${invoice.issuedOn.format(DatePattern)} · ${invoice.invoiceTaxRatePercent}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            invoice.invoiceNumber.takeIf { it.isNotBlank() }?.let { Text("票号：$it", style = MaterialTheme.typography.labelMedium) }
            invoice.attachmentName?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun BreakdownPreview(breakdown: InvoiceBreakdown?) {
    if (breakdown == null) return
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("实时计算", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricBlock("不含税", "¥${breakdown.taxableAmount.format2()}")
                MetricBlock("增值税", "¥${breakdown.vat.format2()}")
                MetricBlock("总税费", "¥${breakdown.totalPayable.format2()}")
            }
        }
    }
}

@Composable
private fun MetricBlock(label: String, value: String) {
    Column(modifier = Modifier.widthIn(min = 96.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    OutlinedCard(modifier = Modifier.widthIn(min = 120.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun PersonPicker(people: List<Person>, selectedPersonId: String, onSelected: (Person) -> Unit, onAddPerson: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = people.firstOrNull { it.id == selectedPersonId } ?: people.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("所属人员", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Business, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(selected?.displayName ?: "请选择人员", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                people.forEach { person ->
                    DropdownMenuItem(text = { Text(person.displayName) }, onClick = { expanded = false; onSelected(person) })
                }
                Divider()
                DropdownMenuItem(text = { Text("新增人员") }, onClick = { expanded = false; onAddPerson() })
            }
        }
    }
}

@Composable
private fun ImportButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun QuarterSelector(selectedYear: Int, selectedQuarter: Int, onChange: (Int, Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(1, 2, 3, 4).forEach { quarter ->
            FilterChip(selected = selectedQuarter == quarter, onClick = { onChange(selectedYear, quarter) }, label = { Text("Q$quarter") })
        }
    }
}

private fun LedgerUiState.currentQuarterInvoices(): List<Invoice> =
    invoices.filter { it.issuedOn.year == selectedYear && quarterOf(it.issuedOn) == selectedQuarter }

private fun AppTab.icon() = when (this) {
    AppTab.Overview -> Icons.Default.Home
    AppTab.Entry -> Icons.Default.Add
    AppTab.Quarter -> Icons.Default.Calculate
    AppTab.People -> Icons.Default.People
    AppTab.Settings -> Icons.Default.Settings
}

private fun InvoiceDraft.toInvoicePreview(state: LedgerUiState): InvoiceBreakdown? {
    if (personId.isBlank()) return null
    val draftInvoice = Invoice(
        personId = personId,
        grossAmount = grossAmount,
        invoiceTaxRatePercent = invoiceTaxRatePercent,
        issuedOn = issuedOn,
        attachmentName = attachmentName,
        attachmentPath = attachmentPath,
        sourceFormat = sourceFormat,
        note = note,
        invoiceNumber = invoiceNumber,
    )
    val quarterInvoices = state.currentQuarterInvoices().filter { it.issuedOn <= issuedOn }
    return invoiceBreakdown(draftInvoice, quarterInvoices, state.taxSettings)
}

private fun Double.format2(): String = "%.2f".format(this)
private fun BigDecimal.format2(): String = setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

private fun LedgerSnapshot.toUiState(seedPeople: List<Person>): LedgerUiState {
    val effectivePeople = if (people.isEmpty()) seedPeople else people
    val first = effectivePeople.firstOrNull()
    return LedgerUiState(
        people = effectivePeople,
        invoices = invoices,
        activeTab = activeTab,
        selectedYear = selectedYear,
        selectedQuarter = selectedQuarter,
        taxSettings = taxSettings,
        draft = InvoiceDraft(
            personId = first?.id.orEmpty(),
            issuedOn = LocalDate.now(),
            invoiceTaxRatePercent = first?.defaultInvoiceTaxRatePercent ?: 1,
        ),
    )
}
