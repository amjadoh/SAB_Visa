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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
                val prefs=remember { getSharedPreferences("appearance",MODE_PRIVATE) }
                var dark by remember { mutableStateOf(prefs.getBoolean("dark",false)) }
                val colors=if(dark) darkColorScheme(primary=Color(0xFF76D2BB),secondary=Color(0xFFE8BB72),background=Color(0xFF11272B),surface=Color(0xFF1C353A)) else lightColorScheme(primary=Color(0xFF176B60),secondary=Color(0xFF415D66),background=Color(0xFFF2F6F5),surface=Color.White)
                MaterialTheme(colorScheme=colors) {
                    App(vm=model,onTheme={ dark=!dark; prefs.edit().putBoolean("dark",dark).apply() },onPermissions={
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

@Composable fun App(onPermissions: () -> Unit = {}, onNotifications: () -> Unit = {}, onImportTracking: () -> Unit = {}, vm: CardViewModel = viewModel(), onTheme: ()->Unit = {}) {
    val data by vm.data.collectAsState()
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val updateBusy by vm.updateBusy.collectAsState()
    val updateStatus by vm.updateStatus.collectAsState()
    val progress by vm.downloadProgress.collectAsState()
    val ready by vm.downloadReady.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var rows by remember(tab) { mutableIntStateOf(100) }
    var form by remember { mutableStateOf<Form?>(null) }
    var consent by remember { mutableStateOf(false) }
    var erase by remember { mutableStateOf(false) }
    var connectUpdates by remember { mutableStateOf(false) }
    var updateToken by remember { mutableStateOf("") }
    val context=androidx.compose.ui.platform.LocalContext.current
    fun openPage(url: String) { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url))) }
    val b=data?.book
    val now=Instant.now()
    val debugBuild=(vm.getApplication<android.app.Application>().applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
    Scaffold(bottomBar={ if(b!=null) NavigationBar {
        listOf("الرئيسية","التحليل","الأقساط","الحركات","الإعدادات").forEachIndexed { index,label ->
            NavigationBarItem(selected=tab==index,onClick={tab=index},icon={Text(listOf("◉","✉","▦","≡","⚙")[index])},label={Text(label,style=MaterialTheme.typography.labelSmall)})
        }
    } }) { padding ->
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding).then(if(tab==1 || tab==3) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("SAB Visa",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            Text("مساحتك المالية • معالجة محلية",color=MaterialTheme.colorScheme.secondary)
            if(debugBuild) Text("نسخة تجريبية لاختبار القراءة والتنبيهات على الجوال",style=MaterialTheme.typography.bodySmall)
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(status.isNotBlank()) Text(status,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("status"))
            data?.updateAccess?.available?.takeIf { it.newerThan(ReleaseUpdates.installedCode(context)) }?.let { update ->
                Panel("يتوفر تحديث",true) {
                    Text("الإصدار ${update.name} جاهز للتنزيل الخاص")
                    Button(onClick={tab=4},modifier=Modifier.testTag("download-update")) { Text("فتح مركز التحديث") }
                    Text("التنزيل والتحقق داخل التطبيق؛ التثبيت بموافقة Android.",style=MaterialTheme.typography.bodySmall)
                }
            }
            if(data==null) {
                Text("جارٍ فتح التخزين المشفر، أو تعذّر فتحه. بياناتك لا تُستبدل تلقائياً.")
                OutlinedButton(onClick={vm.action { vm.vault.load(); Unit }}) { Text("إعادة المحاولة") }
            } else if(b==null) {
                Panel("إعداد مرة واحدة") {
                    Text("حدد الحد الائتماني وآخر أربع خانات. يقرأ التطبيق الشراء والسداد والرصيد من رسائل SAB تلقائياً؛ وتنشأ خطط الأقساط من رسالة التأكيد دون إدخال المبلغ أو المدة.")
                    Button(modifier=Modifier.testTag("setup"),onClick={
                        form=Form("إعداد البطاقة","أدخل آخر أربع خانات من رقم البطاقة كما تظهر في رسالة البنك، وليست الرقم السري PIN. لا تدخل رقم البطاقة الكامل. يمكن تصحيحها لاحقاً من الإعدادات.",listOf(Input("limit","الحد الائتماني بالريال","10000"),Input("last4","آخر 4 خانات من البطاقة في SMS؛ ليست PIN"))) { values ->
                            val config=Config(positive(values.getValue("limit")),0,Instant.EPOCH,digits(values.getValue("last4")))
                            vm.action { vm.vault.change { check(it.book==null); it.copy(book=Book(config)) }; vm.status.value="تم الإعداد؛ فعّل قراءة رسائل SAB مرة واحدة" }
                        }
                    }) { Text("إعداد البطاقة") }
                }
            } else when(tab) {
                0 -> NewDashboard(b,now) { consent=true }
                1 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) { AnalysisScreen(b,now,data!!.inbox) }
                5 -> {
                    Text("رسائل البطاقة",style=MaterialTheme.typography.titleLarge)
                    Text("الرسائل الواضحة تُحتسب تلقائياً. غير المدعوم أو المختلف في البطاقة يُستبعد من الحسابات.")
                    if(data!!.inbox.any { it.status=="OTHER_CARD" }) Text("توجد رسائل برقم بطاقة لا يطابق الإعداد. إذا كانت بطاقتك، استخدم «تصحيح رقم البطاقة» في الإعدادات بالخانات الظاهرة في SMS، وليست الرقم السري.",style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={consent=true}) { Text("صلاحية قراءة الرسائل") }
                    OutlinedButton(onClick={vm.importAll(true)},enabled=!busy) { Text("مزامنة الرسائل التاريخية") }
                    data!!.inbox.sortedByDescending { it.parsed.at }.take(rows).forEach { item ->
                        val p=item.parsed
                        val label=when(item.status) { "AUTO" -> "احتُسبت تلقائياً"; "DUPLICATE" -> "مكررة؛ لم تحتسب"; "OTHER_CARD" -> "رقم البطاقة غير مطابق للإعداد"; "LEGACY" -> "سجل سابق محفوظ"; "HISTORY" -> "ضمن بداية التتبع السابقة"; "FUTURE" -> "تاريخ مستقبلي؛ مستبعدة"; "IGNORED" -> "مستبعدة"; else -> "تحتاج تحققاً؛ لم تحتسب" }
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
                        InstallmentStrip(b,p,now)
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
                    Text("الحركات والرسائل",style=MaterialTheme.typography.titleLarge)
                    var paymentsOnly by remember { mutableStateOf(false) }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected=!paymentsOnly,onClick={paymentsOnly=false},label={Text("الكل")})
                        FilterChip(selected=paymentsOnly,onClick={paymentsOnly=true},label={Text("السداد")})
                        TextButton(onClick={tab=5}) { Text("مصادر SMS") }
                    }
                    val events=data!!.inbox.filter { it.status=="AUTO" && it.parsed.type in (if(paymentsOnly) setOf(SmsType.PAYMENT) else setOf(SmsType.PAYMENT,SmsType.PURCHASE,SmsType.REFUND,SmsType.FEE)) }.sortedByDescending { it.parsed.at }
                    LazyColumn(Modifier.weight(1f).testTag("activity-list"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        items(events,key={it.id}) { item ->
                            val p=item.parsed
                            Panel(when(p.type) { SmsType.PAYMENT -> "سداد مؤكد"; SmsType.REFUND -> "استرجاع"; SmsType.FEE -> "رسوم"; else -> "شراء" }) {
                                Text("${sar(p.amount ?: 0)} ر.س",style=MaterialTheme.typography.titleLarge)
                                Text(p.merchant ?: local(p.at),style=MaterialTheme.typography.bodySmall)
                                if(p.type==SmsType.PAYMENT) {
                                    val allocated=b.entries.filter { it.sourceId?.startsWith("auto:${item.id}:")==true }.sumOf { it.principalPaid }
                                    Text("أصل قسط ${sar(allocated)} • إضافي ${sar((p.amount ?: 0)-allocated)} ر.س")
                                }
                                Text("المصدر: SMS • ${local(p.at)}",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        if(events.isEmpty()) item { Text("لا توجد حركات مطابقة؛ راجع مصادر SMS أو صلاحية القراءة") }
                        items(b.entries.filter { !it.voided && it.sourceId?.startsWith("auto:")!=true && (!paymentsOnly || it.kind==Kind.PAYMENT) }.sortedByDescending { it.at },key={"legacy:"+it.id}) { e ->
                            Panel(kindName(e.kind)) { Text("${sar(e.amount)} ر.س"); Text(e.note) }
                        }
                    }
                }
                4 -> {
                    OutlinedButton(onClick=onTheme) { Text("تبديل المظهر الفاتح / الداكن") }
                    Panel("مطابقة البطاقة مع SMS") {
                        Text("يجب أن تطابق الخانات رقم البطاقة الظاهر في رسالة البنك؛ لا تدخل الرقم السري PIN.")
                        OutlinedButton(modifier=Modifier.testTag("change-card"),onClick={
                            form=Form("تصحيح رقم البطاقة","أدخل آخر أربع خانات كما تظهر في SMS. يعيد التطبيق معالجة الرسائل المحفوظة ويُظهر الخطة المطابقة دون إعادة إدخال العمليات. لا تدخل PIN.",listOf(Input("last4","آخر 4 خانات من البطاقة في SMS؛ ليست PIN"))) { values ->
                                val number=digits(values.getValue("last4").trim())
                                require(number.matches(Regex("[0-9]{4}"))) { "أدخل أربع خانات من البطاقة كما تظهر في SMS" }
                                vm.correctCard(number)
                            }
                        }) { Text("تصحيح رقم البطاقة") }
                    }
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
                    OutlinedButton(modifier=Modifier.testTag("test-notification"),onClick={
                        onNotifications()
                        val posted=notifyLocal(vm.getApplication(),4,"اختبار تنبيهات SAB Visa","تنبيه تجريبي من التطبيق؛ لا يمثل عملية بنكية ولا يغير الرصيد.")
                        vm.status.value=if(posted) "أُرسل تنبيه الاختبار للنظام؛ تحقق من ظهوره على الجوال" else "اسمح بالإشعارات وقناة تذكيرات البطاقة، ثم اضغط الاختبار مجدداً"
                    }) { Text("إرسال تنبيه تجريبي") }
                    Panel("تحديثات التطبيق الخاصة") {
                        val access=data!!.updateAccess
                        if(access.token.isEmpty() && updateStatus.isNotBlank()) Text(updateStatus,style=MaterialTheme.typography.bodySmall)
                        Text("الإصدار المثبت ${ReleaseUpdates.installedName(context)} • فحص رقم الإصدار من GitHub دون إرسال الرسائل أو الحسابات")
                        if(access.token.isEmpty()) {
                            Text("الربط اختياري. أنشئ رمزاً محدوداً لمستودع SAB_Visa، بصلاحية Contents: Read-only فقط، ثم أدخله هنا. يُحفظ مشفراً ولا يُرسل إلا إلى GitHub.",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick={openPage(ReleaseUpdates.tokenSetupUrl)}) { Text("إنشاء رمز قراءة GitHub") }
                            OutlinedButton(onClick={connectUpdates=true},modifier=Modifier.testTag("connect-updates")) { Text("ربط تنبيهات التحديث") }
                        } else {
                            Text("مرتبط؛ فحص تلقائي يومي تقريبي وعند فتح التطبيق")
                            if(access.checkedAt.isNotBlank()) Text("آخر فحص: ${local(Instant.parse(access.checkedAt))}",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick={onNotifications();vm.checkUpdates()},enabled=!updateBusy,modifier=Modifier.testTag("check-update")) { Text(if(updateBusy && progress<0) "جارٍ العمل…" else "فحص التحديث الآن") }
                            Text(updateStatus,modifier=Modifier.testTag("update-status"),style=MaterialTheme.typography.bodyMedium)
                            if(progress>=0) {
                                LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth())
                                Text("تنزيل $progress%")
                                TextButton(onClick={vm.cancelUpdate()}) { Text("إلغاء التنزيل") }
                            }
                            if(access.available?.newerThan(ReleaseUpdates.installedCode(context))==true && !ready) Button(onClick={vm.downloadUpdate()},enabled=!updateBusy) { Text("تنزيل التحديث داخل التطبيق") }
                            if(ready) Button(onClick={vm.installUpdate()},enabled=!updateBusy) { Text("تثبيت التحديث") }
                            TextButton(onClick={context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName))}) { Text("إعدادات إشعارات Android") }
                            TextButton(onClick={vm.disconnectUpdates()},enabled=!updateBusy) { Text("إلغاء ربط التحديثات") }
                        }
                    }
                    Panel("الخصوصية") {
                        Text("المعالجة والتخزين المشفر على جهازك. الإنترنت لفحص إصدارات GitHub الخاصة فقط عند ربطه؛ لا تُرفع الرسائل أو الحسابات. لا إعلانات أو نسخ سحابي، ولا نحفظ النص الأصلي أو رموز التحقق أو أرقام الحسابات الكاملة.")
                        Text("لا يوجد قفل بيومتري مستقل أو تصدير في هذه النسخة. إزالة التطبيق تفقد بياناته. لا يضمن التطبيق تجنب الأرباح؛ كشف البنك وشروطه هما المرجع.",style=MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick={erase=true},enabled=!updateBusy && !busy) { Text("حذف بيانات التطبيق") }
                }
            }
        }
    }
    form?.let { FormDialog(it,busy) { form=null } }
    if(connectUpdates) AlertDialog(onDismissRequest={connectUpdates=false;updateToken=""},title={Text("ربط تحديثات GitHub")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("أدخل رمز قراءة للمستودع المحدد فقط، لا كلمة مرور حسابك. لا ترسل الرمز في المحادثة. يمكنك إلغاؤه لاحقاً من إعدادات GitHub.")
            OutlinedTextField(value=updateToken,onValueChange={updateToken=it},label={Text("رمز القراءة المقيد")},singleLine=true,
                visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),modifier=Modifier.testTag("update-token"))
        }
    },confirmButton={TextButton(enabled=!updateBusy && updateToken.isNotBlank(),onClick={vm.connectUpdates(updateToken);updateToken="";connectUpdates=false;onNotifications()}) { Text("ربط") }},dismissButton={TextButton(onClick={connectUpdates=false;updateToken=""}) { Text("إلغاء") }})
    if(consent) AlertDialog(onDismissRequest={consent=false},title={Text("قراءة رسائل SAB محلياً")},text={Text("بعد منح الإذن تُستورد الرسائل التاريخية للمرسل المعتمد وتُسجّل عمليات البطاقة تلقائياً. رموز التحقق والإيداعات والحوالات ومكافآت المحفظة مستبعدة. يمكنك سحب الإذن من إعدادات Android.")},confirmButton={TextButton(onClick={consent=false;onPermissions()}) { Text("متابعة") }},dismissButton={TextButton(onClick={consent=false}) { Text("إلغاء") }})
    if(erase) AlertDialog(onDismissRequest={erase=false},title={Text("حذف البيانات المحلية؟")},text={Text("يُحذف سجل التطبيق وخططه ومفتاحه دون نسخة احتياطية. رسائل الهاتف الأصلية لا تُحذف.")},confirmButton={TextButton(onClick={erase=false;vm.erase()}) { Text("حذف") }},dismissButton={TextButton(onClick={erase=false}) { Text("إلغاء") }})
}

@Composable private fun Panel(title: String, highlight: Boolean=false, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=if(highlight) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) { Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold); content() }
    }
}
