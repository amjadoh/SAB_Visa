package com.sabvisa.local

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sabvisa.core.*
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var model: CardViewModel
    private val trackingProfile=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) model.importTracking(uri) }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> if(result[Manifest.permission.READ_SMS]==true) model.importAll() }
    override fun onResume() {
        super.onResume()
        if(::model.isInitialized) model.syncIfAllowed()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model=androidx.lifecycle.ViewModelProvider(this)[CardViewModel::class.java]
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF176B60),secondary=Color(0xFF415D66),background=Color(0xFFF4F7F5),surface=Color.White)) {
                    App(vm=model,onPermissions={
                        permissions.launch(buildList {
                            add(Manifest.permission.READ_SMS); add(Manifest.permission.RECEIVE_SMS)
                            if(Build.VERSION.SDK_INT>=33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }.toTypedArray())
                    }, onNotifications={ if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },onImportTracking={trackingProfile.launch(arrayOf("application/json","text/plain"))})
                }
            }
        }
    }
}
private val localFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
private fun local(at: Instant): String = localFormat.format(at.atZone(RIYADH))
private fun instant(text: String): Instant = LocalDateTime.parse(digits(text.trim())).atZone(RIYADH).toInstant()
private fun positive(text: String): Long = money(text).also { require(it>0) { "المبلغ يجب أن يكون أكبر من صفر" } }
private fun date(text: String): LocalDate = LocalDate.parse(digits(text.trim()))
private fun notFuture(at: Instant): Instant = at.also { require(it<=Instant.now()) { "لا تُسجّل عملية مستقبلية قبل تأكيدها" } }
private val kinds = listOf("PURCHASE" to "شراء", "PAYMENT" to "سداد مؤكد", "REFUND" to "استرجاع إلى البطاقة", "FEE" to "رسوم", "CASH" to "سحب نقدي", "ADJUSTMENT" to "تسوية موقعة (+ دين / - تخفيض)")
private fun kindName(k: Kind) = kinds.first { it.first==k.name }.second
private data class Input(val key: String, val label: String, val initial: String="", val options: List<Pair<String,String>> = emptyList())
private data class Form(val title: String, val help: String, val fields: List<Input>, val save: (Map<String,String>) -> Unit)

@Composable private fun FormDialog(form: Form, busy: Boolean, onClose: () -> Unit) {
    val values = remember(form) { mutableStateMapOf<String,String>().apply { form.fields.forEach { put(it.key,it.initial) } } }
    var error by remember(form) { mutableStateOf("") }
    var previous by remember(form) { mutableStateOf(false) }
    AlertDialog(onDismissRequest=onClose,title={ Text(form.title) },text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(form.help,style=MaterialTheme.typography.bodySmall)
            form.fields.forEach { f ->
                if(f.key=="previous" && !previous) TextButton(onClick={previous=true}) { Text("الخطة بدأت سابقاً؟") }
                else if(f.options.isEmpty()) OutlinedTextField(value=values[f.key].orEmpty(),onValueChange={ values[f.key]=it },label={Text(f.label)},modifier=Modifier.fillMaxWidth().testTag(f.key),singleLine=true)
                else {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth()) { Text(f.label+": "+(f.options.find { it.first==values[f.key] }?.second ?: "اختر")) }
                        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
                            f.options.forEach { (key,label) -> DropdownMenuItem(text={Text(label)},onClick={values[f.key]=key;open=false}) }
                        }
                    }
                }
            }
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={ Button(enabled=!busy,onClick={ try { form.save(values.toMap()); onClose() } catch(e: Exception) { error=e.message ?: "تحقق من الحقول" } }) { Text("حفظ") } },dismissButton={ TextButton(onClick=onClose) { Text("إلغاء") } })
}

@Composable fun App(onPermissions: () -> Unit = {}, onNotifications: () -> Unit = {}, onImportTracking: () -> Unit = {}, vm: CardViewModel = viewModel()) {
    val data by vm.data.collectAsState()
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var rows by remember(tab) { mutableIntStateOf(100) }
    var form by remember { mutableStateOf<Form?>(null) }
    var consent by remember { mutableStateOf(false) }
    var erase by remember { mutableStateOf(false) }
    val b=data?.book
    val now=Instant.now()
    val debugBuild=(vm.getApplication<android.app.Application>().applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
    Scaffold(bottomBar={ if(b!=null) NavigationBar {
        listOf("الرئيسية","الرسائل","الأقساط","السجل","الإعدادات").forEachIndexed { index,label ->
            NavigationBarItem(selected=tab==index,onClick={tab=index},icon={Text(listOf("◉","✉","▦","≡","⚙")[index])},label={Text(label,style=MaterialTheme.typography.labelSmall)})
        }
    } }) { padding ->
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("SAB Visa",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            Text("متابعة تلقائية من رسائل البطاقة",color=MaterialTheme.colorScheme.secondary)
            if(debugBuild) Text("نسخة تجريبية لاختبار القراءة والتنبيهات على الجوال",style=MaterialTheme.typography.bodySmall)
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(status.isNotBlank()) Text(status,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("status"))
            if(data==null) {
                Text("جارٍ فتح التخزين المشفر، أو تعذّر فتحه. بياناتك لا تُستبدل تلقائياً.")
                OutlinedButton(onClick={vm.action { vm.vault.load(); Unit }}) { Text("إعادة المحاولة") }
            } else if(b==null) {
                Panel("إعداد مرة واحدة") {
                    Text("حدد الحد الائتماني وآخر أربع خانات. يقرأ التطبيق الشراء والسداد والرصيد من رسائل SAB تلقائياً؛ وتنشأ خطط الأقساط من رسالة التأكيد دون إدخال المبلغ أو المدة.")
                    Button(modifier=Modifier.testTag("setup"),onClick={
                        form=Form("إعداد البطاقة","الحد المسجل قابل للتعديل لاحقاً من الإعدادات. لا تدخل رقم البطاقة الكامل.",listOf(Input("limit","الحد الائتماني بالريال","10000"),Input("last4","آخر أربع خانات"))) { values ->
                            val config=Config(positive(values.getValue("limit")),0,Instant.EPOCH,digits(values.getValue("last4")))
                            vm.action { vm.vault.change { check(it.book==null); it.copy(book=Book(config)) }; vm.status.value="تم الإعداد؛ فعّل قراءة رسائل SAB مرة واحدة" }
                        }
                    }) { Text("إعداد البطاقة") }
                }
            } else when(tab) {
                0 -> {
                    val live=b.liveBalance(now)
                    val summary=b.repaymentSummary(now)
                    Panel("المطلوب قبل يوم ${b.config.closeDay}",true) {
                        Text(summary?.let { "${sar(it.targetBeforeClose)} ر.س" } ?: "بانتظار رسالة رصيد",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
                        summary?.let {
                            Text("المشتريات والرسوم ${sar(it.purchasesToClear)} ر.س")
                            Text("قسط الدورة غير المسدد حسب التوزيع ${sar(it.cyclePrincipal)} ر.س")
                            if(it.cyclePrincipal>0 && it.principalInNextPayment==0L) Text("تخصيص القسط يبدأ ${b.installmentDate(now)}؛ السداد قبله إضافي ولا ينقص أصل الخطة.",style=MaterialTheme.typography.bodySmall)
                        }
                        live?.let { Text("استناداً إلى رصيد SMS بتاريخ ${local(it.anchor.at)}",style=MaterialTheme.typography.bodySmall) }
                        if(b.priorAllocations.isNotEmpty()) Text("قسط سابق باعتمادك؛ بانتظار مطابقة الكشف القادم",style=MaterialTheme.typography.bodySmall)
                        Text("المجموع يشمل القسط مرة واحدة. المشتريات أو الرسوم اللاحقة تغير الهدف، والمستحق البنكي قد يختلف.",style=MaterialTheme.typography.bodySmall)
                        Text("هدف يوم ${b.config.closeDay} لا يؤخر استحقاق البنك؛ سدد المستحق بحلول التاريخ المذكور في الكشف.",style=MaterialTheme.typography.bodySmall)
                    }
                    Panel(if(live?.estimated==true) "رصيد تقديري بعد آخر رسالة" else "رصيد البطاقة من SMS") {
                        Text(live?.let { "${sar(it.available)} ر.س" } ?: "بانتظار رسالة رصيد",style=MaterialTheme.typography.titleLarge)
                        Text("الحد الائتماني ${sar(b.limitAt(now))} ر.س")
                        summary?.let { Text("سقف المشتريات بعد حجز الأقساط ${sar(it.purchaseCapacity)} ر.س") }
                        live?.let {
                            Text("المستخدم من الحد ${sar(it.used)} ر.س")
                            Text("وقت آخر رصيد: ${local(it.anchor.at)}",style=MaterialTheme.typography.bodySmall)
                        }
                        Text("يتحدث الرصيد عند وصول SMS. رسائل الإيداع والحوالات لا تدخل في حساب البطاقة.",style=MaterialTheme.typography.bodySmall)
                    }
                    val principal=b.plans.sumOf { b.remaining(it,now) }
                    Panel("الأقساط وغير المقسط") {
                        Text("موعد سداد القسط المخطط: يوم ${b.config.installmentDay} من الشهر")
                        Text("أصل الأقساط المتبقي ${sar(principal)} ر.س")
                        live?.let {
                            Text("غير المقسط التقديري ${sar((it.used-principal).coerceAtLeast(0))} ر.س")
                            if(principal>it.used.coerceAtLeast(0)) Text("راجع الخطة: أصل الأقساط المسجل أكبر من المستخدم حسب آخر رسالة.",color=MaterialTheme.colorScheme.error)
                        }
                        Text("بعد إقفال الدورة يوم ${b.config.closeDay}، يبدأ تخصيص أصل قسط واحد لكل خطة من موعد السداد المخطط يوم ${b.config.installmentDay}؛ ما قبله وما يزيد على القسط سداد إضافي. هذا تقدير متابعة وليس بياناً من البنك.",style=MaterialTheme.typography.bodySmall)
                    }
                    val month=LocalDate.now(RIYADH).withDayOfMonth(1)
                    val spending=b.spending(month.atStartOfDay(RIYADH).toInstant(),month.plusMonths(1).atStartOfDay(RIYADH).toInstant())
                    Panel("مشتريات الشهر") {
                        Text("${sar(spending)} ر.س",style=MaterialTheme.typography.titleLarge)
                        if(b.config.budget>0) Text("من ميزانية ${sar(b.config.budget)} ر.س • المتبقي ${sar(b.config.budget-spending)}")
                    }
                    b.statements.sortedByDescending { it.issued }.take(3).forEach { s -> Panel("إشعار كشف رُصد ${s.issued}") {
                        Text("المبلغ المذكور في رسالة الكشف ${sar(s.amount)} ر.س")
                        Text("الاستحقاق ${s.due} • تحقق من كامل الكشف لدى البنك",style=MaterialTheme.typography.bodySmall)
                    } }
                    val context=androidx.compose.ui.platform.LocalContext.current
                    if(context.checkSelfPermission(Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED) Button(onClick={consent=true}) { Text("تفعيل قراءة رسائل SAB") }
                }
                1 -> {
                    Text("رسائل البطاقة",style=MaterialTheme.typography.titleLarge)
                    Text("الرسائل الواضحة تُحتسب تلقائياً. غير المدعوم أو المختلف في البطاقة يُستبعد من الحسابات.")
                    OutlinedButton(onClick={consent=true}) { Text("صلاحية قراءة الرسائل") }
                    OutlinedButton(onClick={vm.importAll()},enabled=!busy) { Text("مزامنة الرسائل التاريخية") }
                    data!!.inbox.sortedByDescending { it.parsed.at }.take(rows).forEach { item ->
                        val p=item.parsed
                        val label=when(item.status) { "AUTO" -> "احتُسبت تلقائياً"; "DUPLICATE" -> "مكررة؛ لم تحتسب"; "OTHER_CARD" -> "بطاقة أخرى"; "LEGACY" -> "سجل سابق محفوظ"; "HISTORY" -> "ضمن بداية التتبع السابقة"; "FUTURE" -> "تاريخ مستقبلي؛ مستبعدة"; "IGNORED" -> "مستبعدة"; else -> "تحتاج تحققاً؛ لم تحتسب" }
                        Panel(label) {
                            Text("${p.last4 ?: "بطاقة غير محددة"} • ${local(p.at)}")
                            p.amount?.let { Text("${sar(it)} ر.س") }
                            p.available?.let { Text("الرصيد المذكور ${sar(it)} ر.س") }
                            p.merchant?.let { Text(it) }
                            if(item.status!="AUTO") Text(p.reason,style=MaterialTheme.typography.bodySmall)
                            if(item.status=="AUTO" && p.type==SmsType.PAYMENT) {
                                val allocated=b.entries.filter { it.sourceId?.startsWith("auto:${item.id}:")==true }.sumOf { it.principalPaid }
                                Text("توزيع الدفعة: أصل قسط ${sar(allocated)} • مشتريات / سداد إضافي ${sar((p.amount ?: 0)-allocated)} ر.س")
                            }
                        }
                    }
                    if(data!!.inbox.size>rows) TextButton(onClick={rows+=100}) { Text("إظهار المزيد") }
                }
                2 -> {
                    Text("خطط الأقساط",style=MaterialTheme.typography.titleLarge)
                    Text("تُنشأ الخطط تلقائياً من رسالة تأكيد التحويل إلى أقساط، وتُستبعد الرسائل الدعائية.")
                    b.plans.forEach { p -> Panel(p.name) {
                        val remaining=b.remaining(p,now)
                        Text("المتبقي ${sar(remaining)} ر.س",style=MaterialTheme.typography.titleLarge)
                        Text("المبلغ الأصلي ${sar(p.principal)} • المدة ${p.months} شهر")
                        if(remaining>0) Text("أصل القسط لدورة الكشف ${sar(b.monthlyPrincipal(p,b.billingMonth(now)))} ر.س")
                        Text("أصل خُصص من دفعات هذه الدورة: ${sar(b.principalPaidIn(p,b.billingMonth(now),now))} ر.س")
                        if(p.paidBeforeTracking>0) Text("أقساط مسددة قبل بدء التتبع: ${p.paidBeforeTracking}")
                        Text("الأقساط المسددة حسب توزيع التطبيق ${b.completedInstallments(p,now)} من ${p.months} • المتبقية ${p.months-b.completedInstallments(p,now)}")
                        val prior=b.priorAllocations.singleOrNull { it.planId==p.id }
                        if(prior!=null) {
                            Text("يشمل قسطاً سابقاً باعتمادك بتاريخ ${local(prior.approvedAt)}؛ لم يُطابق مع الكشف بعد.",style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={vm.removePrior(p.id)}) { Text("إلغاء اعتماد القسط السابق") }
                        } else if(p.paidBeforeTracking==0) {
                            val candidates=data!!.inbox.filter { it.status=="AUTO" && it.parsed.type==SmsType.PAYMENT && it.parsed.at>=p.createdAt && (it.parsed.amount ?: 0)>=p.installment(0) }.sortedByDescending { it.parsed.at }
                            if(candidates.isNotEmpty()) OutlinedButton(onClick={
                                form=Form("اعتماد قسط سابق واحد","يعاد تخصيص أصل قسط واحد من دفعة موجودة، دون إضافة سداد جديد. لا يغطي هذا الاعتماد قسط الدورة الحالية، ويظل بانتظار مطابقة كشف البنك.",listOf(Input("payment","دفعة السداد",candidates.first().id,candidates.map { it.id to "${local(it.parsed.at)} • ${sar(it.parsed.amount!!)} ر.س" }))) { values -> vm.approvePrior(p.id,values.getValue("payment")) }
                            },modifier=Modifier.testTag("approve-prior")) { Text("اعتماد قسط سابق من سداد موجود") }
                        }
                    } }
                }
                3 -> {
                    Text("السجل التلقائي",style=MaterialTheme.typography.titleLarge)
                    Text("كل عملية مرتبطة برسالتها. تقسيم السداد إلى أصل قسط وسداد إضافي لا يكرر المبلغ.")
                    b.entries.filterNot { it.voided }.sortedByDescending { it.at }.take(rows).forEach { e -> Panel(kindName(e.kind)) {
                        Text("${sar(e.amount)} ر.س • ${local(e.at)}"); Text(e.note)
                    } }
                    if(b.entries.size>rows) TextButton(onClick={rows+=100}) { Text("إظهار المزيد") }
                }
                4 -> {
                    Panel("الحد الائتماني") {
                        Text("${sar(b.limitAt(now))} ر.س",style=MaterialTheme.typography.titleLarge)
                        OutlinedButton(onClick={
                            form=Form("تعديل الحد الائتماني","حدّث الحد المسجل إلى الحد الذي أقره البنك. يُحفظ التغيير بتاريخه، ويظل الرصيد السابق من الرسالة كما ورد.",listOf(Input("limit","الحد الائتماني الجديد",sar(b.limitAt(now))))) { values -> vm.changeLimit(positive(values.getValue("limit"))) }
                        },modifier=Modifier.testTag("change-limit")) { Text("تعديل الحد الائتماني") }
                    }
                    Panel("دورة الكشف") {
                        Text("يوم الإقفال المسجل: ${b.config.closeDay}")
                        Text("موعد سداد القسط المخطط: يوم ${b.config.installmentDay}")
                        Text("إذا لم يوجد هذا اليوم في الشهر، يُستخدم آخر يوم فيه. موعد الاستحقاق يُقرأ من رسالة البنك بصورة مستقلة.",style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick={form=Form("تعديل يوم إقفال الكشف","طابق اليوم مع تاريخ الكشف نفسه؛ قد تصل رسالة الإشعار بعده. تغيير اليوم يعيد حساب توزيع الرسائل تلقائياً.",listOf(Input("day","يوم الإقفال من 1 إلى 31",b.config.closeDay.toString()))) { values ->
                            val day=digits(values.getValue("day")).toIntOrNull() ?: error("أدخل يوماً صحيحاً")
                            vm.updateBook { it.copy(config=it.config.copy(closeDay=day)) }
                        }},modifier=Modifier.testTag("change-cycle")) { Text("تعديل يوم الإقفال") }
                        OutlinedButton(onClick={form=Form("موعد سداد القسط","قبل هذا الموعد لا يخصص السداد للقسط؛ بعده يخصص قسط واحد للدورة. لا يغيّر ذلك تاريخ استحقاق البنك ولا ينفذ دفعاً.",listOf(Input("installmentDay","يوم السداد المخطط من 1 إلى 31",b.config.installmentDay.toString()))) { values ->
                            val day=digits(values.getValue("installmentDay")).toIntOrNull() ?: error("أدخل يوماً صحيحاً")
                            vm.updateBook { it.copy(config=it.config.copy(installmentDay=day)) }
                        }}) { Text("تعديل موعد القسط") }
                    }
                    OutlinedButton(onClick=onImportTracking) { Text("استيراد اعتماد التتبع") }
                    OutlinedButton(onClick={form=Form("الميزانية والمرسلون","الإيداعات والحوالات مستبعدة دائماً. معرفات المرسلين مفصولة بفاصلة إنجليزية.",listOf(Input("budget","ميزانية الشهر؛ صفر لإيقافها",sar(b.config.budget)),Input("senders","المرسلون المعتمدون",b.config.senders.joinToString(",")))) { values ->
                        val config=b.config.copy(budget=money(values.getValue("budget")),senders=values.getValue("senders").split(",").map { it.trim() }.toSet())
                        vm.updateBook { it.copy(config=config) }
                    }}) { Text("الميزانية والمرسلون") }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("تذكيرات محلية تقريبية")
                        Switch(checked=b.config.reminders,onCheckedChange={ enabled -> if(enabled) onNotifications(); vm.updateBook { scheduleReminders(vm.getApplication(),enabled); it.copy(config=it.config.copy(reminders=enabled)) } })
                    }
                    if(debugBuild) OutlinedButton(modifier=Modifier.testTag("test-notification"),onClick={
                        onNotifications()
                        notifyLocal(vm.getApplication(),99,"اختبار تنبيهات SAB Visa","تنبيه تجريبي من التطبيق؛ لا يمثل عملية بنكية ولا يغير الرصيد.")
                    }) { Text("إرسال تنبيه تجريبي") }
                    Panel("الخصوصية") {
                        Text("المعالجة والتخزين المشفر على جهازك. لا إذن إنترنت ولا إعلانات أو نسخ سحابي. لا نحفظ النص الأصلي أو رموز التحقق أو أرقام الحسابات الكاملة.")
                        Text("لا يوجد قفل بيومتري مستقل أو تصدير في هذه النسخة. إزالة التطبيق تفقد بياناته. لا يضمن التطبيق تجنب الأرباح؛ كشف البنك وشروطه هما المرجع.",style=MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick={erase=true}) { Text("حذف بيانات التطبيق") }
                }
            }
        }
    }
    form?.let { FormDialog(it,busy) { form=null } }
    if(consent) AlertDialog(onDismissRequest={consent=false},title={Text("قراءة رسائل SAB محلياً")},text={Text("بعد منح الإذن تُستورد الرسائل التاريخية للمرسل المعتمد وتُسجّل عمليات البطاقة تلقائياً. رموز التحقق والإيداعات والحوالات ومكافآت المحفظة مستبعدة. يمكنك سحب الإذن من إعدادات Android.")},confirmButton={TextButton(onClick={consent=false;onPermissions()}) { Text("متابعة") }},dismissButton={TextButton(onClick={consent=false}) { Text("إلغاء") }})
    if(erase) AlertDialog(onDismissRequest={erase=false},title={Text("حذف البيانات المحلية؟")},text={Text("يُحذف سجل التطبيق وخططه ومفتاحه دون نسخة احتياطية. رسائل الهاتف الأصلية لا تُحذف.")},confirmButton={TextButton(onClick={erase=false;vm.erase()}) { Text("حذف") }},dismissButton={TextButton(onClick={erase=false}) { Text("إلغاء") }})
}

@Composable private fun Panel(title: String, highlight: Boolean=false, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=if(highlight) Color(0xFFD9F2E8) else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) { Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold); content() }
    }
}
