package com.sabvisa.local

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sabvisa.core.*
import java.time.*

private val Gold=Color(0xFFBD9350)
@Composable fun DesignCard(title: String, content: @Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            content()
        }
    }
}
@Composable fun InstallmentStrip(b: Book,p: Plan,now: Instant) {
    val completed=b.completedInstallments(p,now)
    var selected by remember(p.id) { mutableIntStateOf(-1) }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        // Match the compact preview: one year per row; keep vertical tap space around the small marks.
        (0 until p.months).chunked(12).forEach { indices -> Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            indices.forEach { i ->
                Box(Modifier.weight(1f).height(44.dp).testTag("installment-${p.id}-${i+1}").clickable { selected=i },contentAlignment=androidx.compose.ui.Alignment.Center) {
                Surface(modifier=Modifier.fillMaxWidth().height(28.dp),shape=RoundedCornerShape(5.dp),
                    color=if(i<completed) MaterialTheme.colorScheme.primary else if(i==completed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                    Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.Center) { Text("${i+1}",style=MaterialTheme.typography.labelSmall,maxLines=1) }
                }
                }
            }
            repeat(12-indices.size) { Spacer(Modifier.weight(1f)) }
        } }
        Text("سددت $completed من ${p.months} • المتبقي ${sar(b.remaining(p,now))} ر.س",style=MaterialTheme.typography.bodyMedium)
    }
    if(selected>=0) AlertDialog(onDismissRequest={selected=-1},title={Text("القسط ${selected+1}")},text={Text("أصله ${sar(p.installment(selected))} ر.س • ${if(selected<completed) "مسدد حسب توزيع المتابعة" else "لم يكتمل سداده"}\n${if(b.priorAllocations.any { it.planId==p.id }) "تشمل الخطة اعتماداً سابقاً ينتظر مطابقة البنك" else "التوزيع مستخرج من دفعات السداد المؤكدة"}")},confirmButton={TextButton(onClick={selected=-1}) { Text("إغلاق") }})
}
@Composable fun NewDashboard(b: Book,now: Instant,onPermissions: ()->Unit) {
    val live=b.liveBalance(now)
    val summary=b.repaymentSummary(now)
    var explain by remember { mutableStateOf(false) }
    Text("متابعتك اليوم",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF143E43),contentColor=Color.White)) {
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(if(summary==null) "بانتظار رصيد البطاقة" else if(summary.netBeforeClose<0) "زيادة لصالحك حسب المتابعة" else "المطلوب قبل يوم ${b.config.closeDay}")
            Text(summary?.let { "${sar(if(it.netBeforeClose<0) it.surplusBeforeClose else it.targetBeforeClose)} ر.س" } ?: "—",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
            Text("محسوب • القسط غير المسدد يدخل مرة واحدة",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={explain=true},colors=ButtonDefaults.textButtonColors(contentColor=Color.White)) { Text("كيف انحسب؟") }
        }
    }
    DesignCard(if(live?.estimated==true) "رصيد تقديري بعد آخر رسالة" else "المتاح من آخر رسالة البنك") {
        Text(live?.let { "${sar(it.available)} ر.س" } ?: "بانتظار رسالة رصيد",style=MaterialTheme.typography.headlineMedium)
        Text(live?.let { "${it.anchor.at.atZone(RIYADH).toLocalDateTime()} • ${if(it.estimated) "محسوب" else "من البنك"}" } ?: "فعّل القراءة أو راجع الرسائل غير المحتسبة",style=MaterialTheme.typography.bodySmall)
        summary?.let { Text("المشتريات والرسوم المتبقية ${sar(it.purchasesToClear)} ر.س") }
    }
    if(summary!=null && live!=null) DesignCard("توزيع الحد الائتماني") {
        val limit=b.limitAt(now)
        val principal=limit-summary.purchaseCapacity
        if(live.available>=0 && principal<=limit) {
            val available=minOf(live.available,summary.purchaseCapacity).coerceAtLeast(0)
            val ordinary=(summary.purchaseCapacity-available).coerceAtLeast(0)
            Row(Modifier.fillMaxWidth().height(18.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                listOf(available to MaterialTheme.colorScheme.primary,principal to Gold,ordinary to MaterialTheme.colorScheme.outline).filter { it.first>0 }.forEach { (n,c) -> Box(Modifier.weight((n.toDouble()/limit).toFloat()).fillMaxHeight().background(c,RoundedCornerShape(5.dp))) }
            }
            Text("المتاح ضمن الحد ${sar(available)} • أصل الأقساط ${sar(principal)} • غير المقسط ${sar(ordinary)} ر.س",style=MaterialTheme.typography.bodySmall)
        } else Text("يتجاوز الحجز أو الاستخدام الحد المسجل؛ راجع المطابقة قبل تفسير التوزيع.")
        Text("الحد ${sar(limit)} ر.س")
        if(summary.ordinaryNet<0) Text("زيادة غير المقسط ${sar(-summary.ordinaryNet)} ر.س، تظهر منفصلة عن توزيع الحد",style=MaterialTheme.typography.bodySmall)
    }
    b.plans.forEach { p -> DesignCard(p.name) { InstallmentStrip(b,p,now) } }
    DesignCard("المواعيد") {
        Text("هدف تصفية المشتريات: يوم ${b.config.closeDay}")
        Text("بدء تخصيص القسط: ${b.installmentDate(now)}")
        b.statements.maxByOrNull { it.issued }?.let { Text("استحقاق البنك كما ورد: ${it.due} • مبلغ الإشعار ${sar(it.amount)} ر.س") }
        Text("هدف المتابعة مستقل عن استحقاق البنك. توزيعك ينتظر مطابقة الكشف.",style=MaterialTheme.typography.bodySmall)
    }
    val context=androidx.compose.ui.platform.LocalContext.current
    if(context.checkSelfPermission(android.Manifest.permission.READ_SMS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) Button(onClick=onPermissions) { Text("تفعيل قراءة رسائل SAB") }
    if(explain) AlertDialog(onDismissRequest={explain=false},title={Text("تفاصيل صافي المتابعة")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(summary?.let { "${sar(it.netBeforeClose)} ر.س" } ?: "بانتظار رسالة رصيد")
            summary?.let {
                Text("المطلوب دفعه حسب المتابعة ${sar(it.targetBeforeClose)} ر.س")
                Text("رصيد زائد لصالحك حسب المتابعة ${sar(it.surplusBeforeClose)} ر.س")
                Text("سقف المشتريات ${sar(it.purchaseCapacity)} ناقص المتاح ${sar(live!!.available)}")
                Text("صافي المشتريات والرسوم ${sar(it.ordinaryNet)} ر.س")
                Text("قسط الدورة غير المسدد حسب التوزيع ${sar(it.cyclePrincipal)} ر.س")
            }
            Text("السالب زيادة وفق توزيع المتابعة؛ لا يثبت تصنيف البنك ولا يسدد قسطاً مستقبلياً تلقائياً.",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={explain=false}) { Text("إغلاق") }})
}
@Composable fun AnalysisScreen(b: Book,now: Instant, inbox: List<SmsRecord>) {
    var monthly by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(-1) }
    val today=now.atZone(RIYADH).toLocalDate()
    val periods=if(monthly) (2 downTo 0).map { val d=today.withDayOfMonth(1).minusMonths(it.toLong()); d to d.plusMonths(1) }
        else (3 downTo 0).map { val d=today.minusDays(today.dayOfWeek.value.toLong()-1).minusWeeks(it.toLong()); d to d.plusWeeks(1) }
    val points=periods.map { (from,to) -> val e=b.entries.filter { !it.voided && it.at>=from.atStartOfDay(RIYADH).toInstant() && it.at<to.atStartOfDay(RIYADH).toInstant() && it.at<=now }; Triple(e.filter { it.kind==Kind.PURCHASE }.sumOf { it.amount },e.filter { it.kind==Kind.PAYMENT }.sumOf { it.amount },e.sumOf { it.principalPaid }) }
    val max=points.maxOfOrNull { maxOf(it.first,it.second) }?.coerceAtLeast(1) ?: 1L
    Text("الصرف والسداد",style=MaterialTheme.typography.headlineMedium)
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { FilterChip(selected=!monthly,onClick={monthly=false;selected=-1},label={Text("أسبوعي")}); FilterChip(selected=monthly,onClick={monthly=true;selected=-1},label={Text("شهري")}) }
    DesignCard("مقارنة العمليات المسجلة • ريال") {
        Row(Modifier.fillMaxWidth().height(210.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            points.forEachIndexed { i,p -> Column(Modifier.weight(1f).clickable { selected=i }) {
                Row(Modifier.weight(1f).fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.weight(1f).height(if(p.first==0L) 0.dp else (p.first.toDouble()/max*150).dp.coerceAtLeast(2.dp)).background(MaterialTheme.colorScheme.primary,RoundedCornerShape(topStart=5.dp,topEnd=5.dp)))
                    Box(Modifier.weight(1f).height(if(p.second==0L) 0.dp else (p.second.toDouble()/max*150).dp.coerceAtLeast(2.dp)).background(Gold,RoundedCornerShape(topStart=5.dp,topEnd=5.dp)))
                }
                Text(if(monthly) "${periods[i].first.monthValue}/${periods[i].first.year}" else "${periods[i].first.dayOfMonth}/${periods[i].first.monthValue}",style=MaterialTheme.typography.labelSmall)
                TextButton(onClick={selected=i},contentPadding=PaddingValues(0.dp)) { Text("تفاصيل",style=MaterialTheme.typography.labelSmall) }
            } }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { Text("■ مشتريات",color=MaterialTheme.colorScheme.primary); Text("■ سداد",color=Gold) }
        Text("اضغط الفترة لعرض مبالغها. تشمل المشتريات المسجلة العملية الأصلية إن حُوّلت للتقسيط؛ ليست مقياس المشتريات العادية المتبقية.",style=MaterialTheme.typography.bodySmall)
        if(selected>=0) {
            val p=points[selected]
            if(p.first==0L && p.second==0L) Text("لا سجلات في هذه الفترة؛ لا يؤكد ذلك اكتمال الرسائل")
            Text("مشتريات ${sar(p.first)} • سداد ${sar(p.second)} ر.س")
            Text("من السداد: أصل قسط ${sar(p.third)} • إضافي ${sar(p.second-p.third)} ر.س")
        }
        Text(if(inbox.any { it.status in setOf("REVIEW","OTHER_CARD","FUTURE") }) "توجد رسائل غير محتسبة؛ الفترات قد تكون غير مكتملة" else "حسب العمليات المتاحة للتطبيق؛ اكتمال رسائل الهاتف لا يثبت اكتمال كشف البنك",style=MaterialTheme.typography.bodySmall)
    }
}
