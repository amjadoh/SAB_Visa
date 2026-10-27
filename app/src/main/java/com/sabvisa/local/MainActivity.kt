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
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF176B60),secondary=Color(0xFF415D66),background=Color(0xFFF4F7F5),surface=Color.White)) {
                    App(onPermissions={
                        permissions.launch(buildList {
                            add(Manifest.permission.READ_SMS); add(Manifest.permission.RECEIVE_SMS)
                            if(Build.VERSION.SDK_INT>=33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }.toTypedArray())
                    }, onNotifications={ if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) })
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
    AlertDialog(onDismissRequest=onClose,title={ Text(form.title) },text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(form.help,style=MaterialTheme.typography.bodySmall)
            form.fields.forEach { f ->
                if(f.options.isEmpty()) OutlinedTextField(value=values[f.key].orEmpty(),onValueChange={ values[f.key]=it },label={Text(f.label)},modifier=Modifier.fillMaxWidth().testTag(f.key),singleLine=true)
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

@Composable fun App(onPermissions: () -> Unit = {}, onNotifications: () -> Unit = {}, vm: CardViewModel = viewModel()) {
    val data by vm.data.collectAsState()
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var rowLimit by remember(tab) { mutableIntStateOf(100) }
    var form by remember { mutableStateOf<Form?>(null) }
    var erase by remember { mutableStateOf(false) }
    var voidEntry by remember { mutableStateOf<Entry?>(null) }
    var consent by remember { mutableStateOf(false) }
    val b = data?.book
    fun showEntry(item: Inbox? = null) {
        if(b==null) return
        val p=item?.parsed
        val initialKind = when(p?.type) { SmsType.PURCHASE -> "PURCHASE"; SmsType.PAYMENT -> "PAYMENT"; SmsType.REFUND -> "REFUND"; else -> "PURCHASE" }
        form=Form("تسجيل عملية", "البطاقة المحددة: •••• ${b.config.last4}. خصص أصل القسط فقط عند تأكيد تخصيص البنك. لا تضف قسطاً كمشتريات جديدة. التاريخ بتوقيت الرياض.",
            listOf(Input("kind","النوع",initialKind,kinds),Input("amount","المبلغ بالريال",p?.amount?.let(::sar).orEmpty()),Input("at","التاريخ والوقت yyyy-MM-ddTHH:mm:ss",local(p?.at ?: Instant.now())),Input("note","الوصف / سبب التسوية",p?.merchant ?: "عملية يدوية"),
                Input("plan","خطة تخصيص السداد","",listOf("" to "لا تخصيص")+b.plans.map { it.id to it.name }),Input("principal","أصل القسط ضمن السداد","0"),
                Input("statement","يخص كشف","",listOf("" to "غير مخصص")+b.statements.map { it.id to "${it.issued} — ${sar(it.amount)}" }))) { v ->
            val e=Entry(id(),Kind.valueOf(v.getValue("kind")),money(v.getValue("amount")),notFuture(instant(v.getValue("at"))),v.getValue("note"),item?.id,v["plan"]?.ifEmpty { null },money(v.getValue("principal")),v["statement"]?.ifEmpty { null })
            b.copy(entries=b.entries+e).validate()
            if(item==null) vm.updateBook { it.copy(entries=it.entries+e) } else vm.accept(item,entry=e)
        }
    }
    fun showStatement(item: Inbox? = null) {
        val p=item?.parsed
        form=Form("تسجيل كشف البنك","أدخل المبلغ الذي ستتابع سداده كما هو في الكشف؛ تسمية «المبلغ المستحق» وحدها لا تثبت أنه كامل الرصيد. تاريخ الإصدار وتاريخ الاستحقاق مستقلان عن يوم التخطيط.",
            listOf(Input("issued","تاريخ الإصدار yyyy-MM-dd",(p?.at ?: Instant.now()).atZone(RIYADH).toLocalDate().toString()),Input("due","تاريخ الاستحقاق yyyy-MM-dd",p?.due?.toString().orEmpty()),Input("amount","مبلغ الكشف بالريال",p?.amount?.let(::sar).orEmpty()))) { v ->
            val s=Statement(id(),date(v.getValue("issued")),date(v.getValue("due")),money(v.getValue("amount")))
            require(s.issued<=LocalDate.now(RIYADH)) { "تاريخ إصدار مستقبلي" }
            require(b?.statements?.none { it.issued==s.issued } != false) { "يوجد كشف بهذا التاريخ؛ راجعه قبل الإضافة" }
            if(item==null) vm.updateBook { it.copy(statements=it.statements+s) } else vm.accept(item,statement=s)
        }
    }
    fun showCheckpoint(item: Inbox? = null) {
        val p=item?.parsed
        form=Form("مقارنة رصيد البنك","هذه المقارنة لا تغيّر الدين أو الحد. اختلاف التاريخ أو الحجز المؤقت أو الأقساط أو الرسوم قد يفسّر الفرق. الرصيد في إشعار الرصيد المتوفر قد يكون مقرباً.",
            listOf(Input("available","المتاح لدى البنك بالريال",p?.available?.let(::sar).orEmpty()),Input("at","وقت الرصد yyyy-MM-ddTHH:mm:ss",local(p?.at ?: Instant.now())),Input("note","مصدر الرصد","رصد من البنك"))) { v ->
            val c=Checkpoint(id(),notFuture(instant(v.getValue("at"))),money(v.getValue("available")),v.getValue("note"))
            require(c.at >= (b?.config?.openingAt ?: Instant.EPOCH)) { "الرصد يسبق البداية" }
            if(item==null) vm.updateBook { it.copy(checkpoints=it.checkpoints+c) } else vm.accept(item,checkpoint=c)
        }
    }
    Scaffold(bottomBar={ if(b!=null) NavigationBar {
        listOf("الرئيسية","الرسائل","الأقساط","السجل","الإعدادات").forEachIndexed { index,label ->
            NavigationBarItem(selected=tab==index,onClick={tab=index},icon={ Text(listOf("◉","✉","▦","≡","⚙")[index]) },label={Text(label,style=MaterialTheme.typography.labelSmall)})
        }
    } }) { padding ->
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("SAB Visa",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            Text("متابعة البطاقة على جهازك",color=MaterialTheme.colorScheme.secondary)
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(status.isNotEmpty()) Text(status,modifier=Modifier.testTag("status"),style=MaterialTheme.typography.bodySmall)
            if(data==null) {
                Text("جارٍ فتح التخزين المشفر، أو تعذّر فتحه. لا تتم إعادة ضبط البيانات تلقائياً.")
                OutlinedButton(onClick={vm.action { vm.vault.load(); Unit }}) { Text("إعادة المحاولة") }
            } else if(b==null) {
                Panel("ابدأ من رصيد معروف") {
                    Text("أدخل المديونية غير المقسطة عند نقطة البداية. أضف أصل الأقساط المتبقي بشكل مستقل. الرسائل الأقدم من البداية لا تدخل في الحسابات.")
                    Text("لا يضمن التطبيق تجنّب الأرباح أو الرسوم. كشف البنك وشروط البطاقة هما المرجع.")
                    Button(onClick={
                        form=Form("إعداد البطاقة","لا تدخل رقم البطاقة الكامل. يوم 10 موعد تخطيط قابل للتعديل، وليس استنتاجاً لموعد إصدار البنك. ضع وقت البداية قبل الرسائل التي تريد استيرادها.",
                            listOf(Input("limit","الحد الائتماني بالريال","10000"),Input("ordinary","مديونية غير مقسطة عند البداية","0"),Input("last4","آخر أربع خانات"),Input("opening","وقت البداية yyyy-MM-ddTHH:mm:ss",local(Instant.now())),Input("day","يوم التخطيط الشهري 1–28","10"),Input("budget","ميزانية الشهر بالريال؛ صفر لإيقافها","0"))) { v ->
                            val c=Config(positive(v.getValue("limit")),money(v.getValue("ordinary")),notFuture(instant(v.getValue("opening"))),digits(v.getValue("last4")),digits(v.getValue("day")).toInt(),money(v.getValue("budget")))
                            vm.action { vm.vault.change { check(it.book==null); it.copy(book=Book(c)) }; vm.status.value="تم إعداد البطاقة" }
                        }
                    },modifier=Modifier.testTag("setup")) { Text("إعداد البطاقة") }
                }
            } else when(tab) {
                0 -> {
                    val now=Instant.now()
                    val balance=b.balances(now)
                    val c=cycle(now,b.config.closeDay)
                    Panel("المتاح التقديري",highlight=true) {
                        Text("${sar(balance.available)} ر.س",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
                        Text("حد البطاقة ${sar(b.config.limit)} • إجمالي الدين ${sar(balance.debt)}")
                        Text("قيمة تقديرية؛ قد تختلف الحجوزات والرسوم وتوقيت الترحيل لدى البنك.")
                    }
                    Panel("هدف السداد قبل ${c.statementDate}") {
                        Text("غير المقسط: ${sar(balance.ordinary.coerceAtLeast(0))} ر.س",style=MaterialTheme.typography.titleLarge)
                        Text("أصل الأقساط المحجوز: ${sar(balance.principal)} ر.س")
                        Text("أصل أقساط حل موعدها ولم يخصص لها سداد: ${sar(b.plans.sumOf { b.duePrincipal(it,LocalDate.now(RIYADH)) })} ر.س")
                        Text("الهدف يشمل الرصيد غير المقسط المتراكم؛ لا يستبدل المبلغ المطلوب في كشف البنك ولا يشمل رسوم خطة غير مسجلة.")
                        Button(onClick={showEntry()}) { Text("إضافة شراء أو سداد") }
                    }
                    b.statements.sortedByDescending { it.issued }.forEach { s -> Panel("كشف ${s.issued}") {
                        Text("المسجل ${sar(s.amount)} • الباقي ${sar(b.statementRemaining(s,now))} ر.س")
                        Text("الاستحقاق ${s.due} • تخصيص السداد يدوي ويحتاج مطابقة البنك")
                    } }
                    OutlinedButton(onClick={showStatement()}) { Text("إضافة كشف البنك") }
                    val month=LocalDate.now(RIYADH).withDayOfMonth(1)
                    val spending=b.spending(month.atStartOfDay(RIYADH).toInstant(),month.plusMonths(1).atStartOfDay(RIYADH).toInstant())
                    Panel("موازنة الشهر") {
                        Text("صافي المشتريات بعد الاسترجاع ${sar(spending)} ر.س")
                        if(b.config.budget>0) {
                            Text("الميزانية ${sar(b.config.budget)} • المتبقي ${sar(b.config.budget-spending)}")
                            LinearProgressIndicator(progress={ (spending.toFloat()/b.config.budget).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
                            if(spending>b.config.budget) Text("تجاوزت الميزانية",color=MaterialTheme.colorScheme.error)
                        }
                        Text("دورة التخطيط ${c.start.atZone(RIYADH).toLocalDate()} حتى ${c.statementDate} (النهاية غير مشمولة): ${sar(b.spending(c.start,c.endExclusive))} ر.س")
                        Text("الموازنة تشمل العمليات المسجلة بعد نقطة البداية فقط.")
                    }
                    b.checkpoints.maxByOrNull { it.at }?.let { p -> Panel("آخر مقارنة مع البنك") {
                        Text("${local(p.at)} • لدى البنك ${sar(p.bankAvailable)}")
                        Text("التقدير في الوقت نفسه ${sar(b.balances(p.at).available)} • الفرق ${sar(b.discrepancy(p))} ر.س")
                    } }
                    OutlinedButton(onClick={showCheckpoint()}) { Text("مقارنة المتاح مع البنك") }
                    Text("رسائل تنتظر المراجعة: ${data!!.inbox.count { it.status=="NEW" }}")
                }
                1 -> {
                    Text("مراجعة الرسائل",style=MaterialTheme.typography.titleLarge)
                    Text("لا تدخل الرسالة في الأرصدة حتى تعتمدها. مطابقة المرسل لا تثبت أصالة الرسالة.")
                    OutlinedButton(onClick={consent=true}) { Text("تفعيل قراءة رسائل SAB") }
                    OutlinedButton(onClick={vm.importAll()},enabled=!busy) { Text("استيراد رسائل المرسلين المعتمدين") }
                    OutlinedButton(onClick={
                        form=Form("لصق رسالة يدوياً","يبقى النص في الذاكرة أثناء التحليل فقط. استخدم وقت الاستلام الأصلي لمنع التكرار.",listOf(Input("sender","معرف المرسل","SAB"),Input("received","وقت الاستلام yyyy-MM-ddTHH:mm:ss",local(Instant.now())),Input("body","نص الرسالة"))) { v ->
                            val at=notFuture(instant(v.getValue("received"))); require(v.getValue("body").length<=10000); vm.paste(v.getValue("sender"),v.getValue("body"),at)
                        }
                    }) { Text("لصق رسالة دون صلاحيات") }
                    data!!.inbox.filter { it.status=="NEW" }.sortedByDescending { it.received }.take(rowLimit).forEach { item ->
                        val p=item.parsed
                        Panel("${p.type} • ${p.last4 ?: "بطاقة غير محددة"}") {
                            Text("${p.amount?.let(::sar) ?: "—"} ر.س • ${local(p.at)}")
                            p.merchant?.let { Text(it) }; Text(p.reason)
                            p.available?.let { Text("الرصيد المذكور ${sar(it)} (للمقارنة فقط)") }
                            if(p.last4!=null && p.last4!=b.config.last4) Text("بطاقة مختلفة؛ لا يمكن اعتمادها",color=MaterialTheme.colorScheme.error)
                            else Button(onClick={ when(p.type) { SmsType.STATEMENT -> showStatement(item); SmsType.BALANCE -> showCheckpoint(item); else -> showEntry(item) } }) { Text("مراجعة واعتماد") }
                            TextButton(onClick={vm.dismiss(item)}) { Text("تجاهل") }
                        }
                    }
                    if(data!!.inbox.none { it.status=="NEW" }) Text("لا رسائل معلقة")
                    if(data!!.inbox.count { it.status=="NEW" }>rowLimit) TextButton(onClick={rowLimit+=100}) { Text("إظهار 100 رسالة إضافية") }
                }
                2 -> {
                    Text("خطط الأقساط",style=MaterialTheme.typography.titleLarge)
                    Text("مرور الشهر لا يحرر الحد تلقائياً. يحرر الأصل عند تسجيل سداد مؤكد وتخصيصه للخطة. القسط المعروض أصل فقط، والرسوم تُسجل منفصلة.")
                    Button(onClick={
                        form=Form("إضافة خطة","أدخل أصل المبلغ المتبقي وعدد الأشهر المتبقية. عند تحويل شراء مسجل، اختر التحويل حتى لا يتكرر الدين. لا تستخدم التحويل إذا لم تكن المديونية في رصيد غير المقسط.",
                            listOf(Input("name","اسم الخطة"),Input("principal","أصل المبلغ المتبقي"),Input("months","الأشهر المتبقية"),Input("due","أول استحقاق yyyy-MM-dd"),Input("at","تاريخ بدء التتبع yyyy-MM-ddTHH:mm:ss",local(Instant.now())),
                                Input("mode","مصدر الخطة","convert",listOf("convert" to "تحويل من دين غير مقسط مسجل", "opening" to "قسط قائم عند نقطة البداية", "new" to "شراء مقسط جديد غير مسجل")))) { v ->
                            val mode=v.getValue("mode")
                            val at=if(mode=="opening") b.config.openingAt else notFuture(instant(v.getValue("at")))
                            val p=Plan(id(),v.getValue("name"),positive(v.getValue("principal")),digits(v.getValue("months")).toInt(),date(v.getValue("due")),at)
                            require(at>=b.config.openingAt)
                            val adjustment=if(mode=="convert") {
                                require(at>b.config.openingAt && b.balances(at).ordinary>=p.principal) { "الرصيد غير المقسط لا يغطي التحويل" }
                                listOf(Entry(id(),Kind.ADJUSTMENT,-p.principal,at,"تحويل أصل شراء إلى خطة ${p.name}"))
                            } else if(mode=="new") listOf(
                                Entry(id(),Kind.PURCHASE,p.principal,at,"شراء مقسط جديد: ${p.name}"),
                                Entry(id(),Kind.ADJUSTMENT,-p.principal,at,"نقل أصل الشراء الجديد إلى خطة ${p.name}")
                            ) else emptyList()
                            vm.updateBook { it.copy(plans=it.plans+p,entries=it.entries+adjustment) }
                        }
                    }) { Text("إضافة خطة أقساط") }
                    b.plans.forEach { p -> Panel(p.name) {
                        Text("المتبقي ${sar(b.remaining(p,Instant.now()))} من ${sar(p.principal)} ر.س")
                        Text("${p.months} شهر • أول قسط أصل ${sar(p.installment(0))} ر.س")
                        (0 until p.months).forEach { i -> Text("${i+1}. ${p.due(i)} — ${sar(p.installment(i))} ر.س") }
                    } }
                }
                3 -> {
                    Text("سجل العمليات",style=MaterialTheme.typography.titleLarge)
                    Text("الإلغاء يحتفظ بالعملية في السجل ويعيد حساب الأرصدة. لتصحيح عملية ألغها ثم أدخل البديل.")
                    Button(onClick={showEntry()}) { Text("إضافة عملية يدوية") }
                    b.entries.sortedByDescending { it.at }.take(rowLimit).forEach { e -> Panel(kindName(e.kind)+(if(e.voided) " — ملغاة" else "")) {
                        Text("${sar(e.amount)} ر.س • ${local(e.at)}"); Text(e.note)
                        if(e.principalPaid>0) Text("منها أصل قسط ${sar(e.principalPaid)}")
                        if(!e.voided) TextButton(onClick={voidEntry=e}) { Text("إلغاء العملية مع حفظ أثرها") }
                    } }
                    if(b.entries.size>rowLimit) TextButton(onClick={rowLimit+=100}) { Text("إظهار 100 عملية إضافية") }
                }
                4 -> {
                    Panel("الخصوصية") {
                        Text("تخزين AES-GCM بمفتاح Android Keystore. لا إذن إنترنت، ولا تحليلات أو إعلانات أو نسخ سحابي. لا يُحفظ نص SMS الأصلي أو رموز التحقق. إزالة التطبيق تفقد بياناته ومفتاحه؛ لا يوجد تصدير في هذه النسخة.")
                        Text("حماية الجهاز وقفل شاشته ضروريان؛ لا تتضمن هذه النسخة قفل تطبيق بيومترياً مستقلاً.")
                    }
                    Text("البطاقة •••• ${b.config.last4} • البداية ${local(b.config.openingAt)}")
                    OutlinedButton(onClick={
                        form=Form("إعدادات التخطيط","تغيير موعد التخطيط لا يعدّل الكشوف المسجلة أو تاريخ السداد. معرفات المرسلين مفصولة بفاصلة إنجليزية ويجب أن تطابق المرسل تماماً.",
                            listOf(Input("day","يوم التخطيط",b.config.closeDay.toString()),Input("budget","ميزانية الشهر",sar(b.config.budget)),Input("senders","معرفات المرسلين",b.config.senders.joinToString(",")))) { v ->
                            val updated=b.config.copy(closeDay=digits(v.getValue("day")).toInt(),budget=money(v.getValue("budget")),senders=v.getValue("senders").split(",").map { it.trim() }.toSet())
                            vm.updateBook { it.copy(config=updated) }
                        }
                    }) { Text("تعديل التخطيط والميزانية والمرسلين") }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("تذكيرات محلية تقريبية")
                        Switch(checked=b.config.reminders,onCheckedChange={ enabled ->
                            if(enabled) onNotifications()
                            vm.updateBook { scheduleReminders(vm.getApplication(),enabled); it.copy(config=it.config.copy(reminders=enabled)) }
                        })
                    }
                    Text("تُراجع المواعيد كل 12 ساعة تقريباً؛ قد يؤخرها النظام أو تعطيل الإشعارات. افحص موعد السداد بنفسك.")
                    OutlinedButton(onClick={erase=true}) { Text("حذف جميع بيانات التطبيق") }
                    Text("SAB Visa مشروع مستقل، غير تابع للبنك. الواجهة تعرض تقديرات ولا تنفذ مدفوعات.")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    form?.let { FormDialog(it,busy) { form=null } }
    if(consent) AlertDialog(onDismissRequest={consent=false},title={Text("قراءة الرسائل محلياً")},text={Text("سيقرأ التطبيق رسائل معرفات المرسلين المعتمدين بعد وقت البداية فقط، ويحلل رسائل البطاقة على الجهاز. لا يرسلها إلى أي جهة. يمكنك الرفض واستخدام اللصق اليدوي، وسحب الصلاحية لاحقاً من إعدادات Android.")},confirmButton={TextButton(onClick={consent=false;onPermissions()}) { Text("متابعة إلى صلاحيات Android") }},dismissButton={TextButton(onClick={consent=false}) { Text("إلغاء") }})
    if(erase) AlertDialog(onDismissRequest={erase=false},title={Text("حذف البيانات المحلية؟")},text={Text("سيُحذف سجل التطبيق والأقساط والكشوف والمفتاح. لا توجد نسخة احتياطية داخل التطبيق. رسائل هاتفك الأصلية لا تُحذف.")},confirmButton={TextButton(onClick={erase=false;vm.erase()}) { Text("حذف") }},dismissButton={TextButton(onClick={erase=false}) { Text("إلغاء") }})
    voidEntry?.let { e -> AlertDialog(onDismissRequest={voidEntry=null},title={Text("إلغاء ${sar(e.amount)} ر.س؟")},text={Text("سيعاد احتساب الأرصدة مع الاحتفاظ بأثر العملية.")},confirmButton={TextButton(onClick={voidEntry=null;vm.updateBook { it.copy(entries=it.entries.map { item -> if(item.id==e.id) item.copy(voided=true) else item }) }}) { Text("إلغاء العملية") }},dismissButton={TextButton(onClick={voidEntry=null}) { Text("رجوع") }}) }
}

@Composable private fun Panel(title: String, highlight: Boolean=false, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=if(highlight) Color(0xFFD9F2E8) else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            content()
        }
    }
}
