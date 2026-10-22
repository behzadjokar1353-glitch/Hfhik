package ir.behzad.accounting

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("behzad_data", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private var page = "خانه"
    private var selectedProduct = -1
    private val REQUEST_RESTORE = 741
    private val REQUEST_BACKUP = 742
    private val REQUEST_EXPORT_XLSX = 743
    private val REQUEST_EXPORT_PDF = 744
    private var pendingExportType: String? = null
    private var pendingBarcodeCallback: ((String) -> Unit)? = null
    private val currency get() = prefs.getString("currency", "تومان") ?: "تومان"
    private fun arr(key: String) = try { JSONArray(prefs.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
    private fun save(key: String, value: JSONArray) { prefs.edit().putString(key, value.toString()).apply() }
    private val records get() = arr("records")
    private val products get() = arr("products")
    private val people get() = arr("people")
    private val accounts get() = arr("accounts")
    private val portfolio get() = arr("portfolio_assets")
    private val portfolioOps get() = arr("portfolio_operations")
    private fun now() = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("fa")).format(Date())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        scheduleDailyReminder()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 901)
        }
        showPage("خانه")
        if (!prefs.getBoolean("configured", false)) showCurrencyDialog(true)
    }

    private fun txt(value: String, size: Float = 16f, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(0xFF182321.toInt())
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
    private fun edit(hintText: String, numeric: Boolean = false): EditText =
        EditText(this).apply {
            hint = hintText; textSize = 16f
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            gravity = Gravity.RIGHT
        }
    private fun btn(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    }
    private fun card(body: LinearLayout, title: String, value: String? = null, action: (() -> Unit)? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xFFE1EAE7.toInt())
            }
            elevation = dp(2).toFloat()
            if (action != null) {
                isClickable = true
                setOnClickListener { action() }
            }
        }
        box.addView(txt(title, 14f, true).apply { setTextColor(0xFF52645F.toInt()) })
        if (value != null) box.addView(txt(value, 18f, true).apply { setTextColor(0xFF087F70.toInt()) })
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(10)
        body.addView(box, lp)
    }

    private fun showPage(name: String) {
        page = name
        val reportTheme = name in setOf("خانه", "گزارش‌ها", "ترازنامه", "سود و زیان", "جزئیات گزارش", "پرتفوی مجازی", "آلارم‌ها")
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(if (reportTheme) 0xFF191A20.toInt() else 0xFFF4F7F6.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply {
            text = "حسابداری بهزاد"; textSize = 22f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(-1); gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(13), dp(18), dp(13)); setBackgroundColor(if (reportTheme) 0xFF43205F.toInt() else 0xFF087F70.toInt())
        })
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(16)) }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        when (name) {
            "خانه" -> home(body)
            "فروش" -> invoiceForm(body, "فروش")
            "خرید" -> invoiceForm(body, "خرید")
            "برگشت از فروش" -> invoiceForm(body, "برگشت از فروش")
            "برگشت از خرید" -> invoiceForm(body, "برگشت از خرید")
            "هزینه" -> simpleTransaction(body, "هزینه")
            "درآمد" -> simpleTransaction(body, "درآمد")
            "کالا و انبار" -> inventory(body)
            "اشخاص" -> peoplePage(body)
            "صندوق و بانک" -> accountsPage(body)
            "بدهی‌ها" -> debtsPage(body)
            "چک‌ها" -> checksPage(body)
            "اقساط" -> installmentsPage(body)
            "سوابق" -> history(body)
            "گزارش‌ها" -> reports(body)
            "ترازنامه" -> balanceSheet(body)
            "سند افتتاحیه" -> openingBalancePage(body)
            "سود و زیان" -> profitLoss(body)
            "جزئیات گزارش" -> reportDetails(body, intent.getStringExtra("report_title") ?: "گزارش", intent.getStringExtra("report_type") ?: "")
            "پرتفوی مجازی" -> portfolioPage(body)
            "آلارم‌ها" -> alarmsPage(body)
            "تنظیمات" -> settings(body)
            else -> home(body)
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setBackgroundColor(if (reportTheme) 0xFF242630.toInt() else -1) }
        listOf("عملیات", "گزارش‌ها", "+", "تراکنش‌ها", "خانه").forEach { item ->
            nav.addView(TextView(this).apply {
                text = item; textSize = if (item == "+") 19f else 11f; gravity = Gravity.CENTER
                setPadding(dp(3), dp(12), dp(3), dp(12))
                setTextColor(if (page == item || (item == "خانه" && page == "خانه")) 0xFFB56BE8.toInt() else if (reportTheme) 0xFFB5B5BD.toInt() else 0xFF47534F.toInt())
                setOnClickListener {
                    when (item) {
                        "عملیات" -> moreMenu()
                        "گزارش‌ها" -> showPage("گزارش‌ها")
                        "+" -> AlertDialog.Builder(this@MainActivity).setTitle("ثبت سریع").setItems(arrayOf("فروش", "خرید", "برگشت از فروش", "برگشت از خرید", "درآمد", "هزینه")) { _, which -> showPage(arrayOf("فروش", "خرید", "برگشت از فروش", "برگشت از خرید", "درآمد", "هزینه")[which]) }.show()
                        "تراکنش‌ها" -> showPage("سوابق")
                        else -> showPage("خانه")
                    }
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
    }

    private fun moreMenu() {
        val options = arrayOf("فروش", "خرید", "برگشت از فروش", "برگشت از خرید", "کالا و انبار", "درآمد", "هزینه", "صندوق و بانک", "بدهی‌ها", "چک‌ها", "اقساط", "سند افتتاحیه", "گزارش‌ها", "ترازنامه", "سود و زیان", "پرتفوی مجازی", "آلارم‌ها", "سوابق", "تنظیمات")
        AlertDialog.Builder(this).setTitle("بخش‌های برنامه").setItems(options) { _, i -> showPage(options[i]) }.show()
    }

    private fun home(body: LinearLayout) {
        body.setPadding(dp(12), dp(10), dp(12), dp(12))
        body.addView(txt("بهزاد جوکار", 20f, true).apply { setTextColor(0xFFFFFFFF.toInt()) })
        body.addView(txt("واحد پول: $currency  •  حسابداری شخصی و فروشگاهی", 12f).apply { setTextColor(0xFFB8B7C5.toInt()) })
        var incomes = 0L; var expenses = 0L
        val rs = records
        for (i in 0 until rs.length()) {
            val r = rs.optJSONObject(i) ?: continue
            when (r.optString("type")) {
                "درآمد" -> incomes += r.optLong("amount")
                "هزینه" -> expenses += r.optLong("amount")
                "فروش" -> incomes += r.optLong("total", r.optLong("amount"))
            }
        }
        val gaugeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun gauge(label: String, amount: Long, color: Int) {
            val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), dp(12)); background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF242630.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), color) } }
            g.addView(txt(label, 14f, true).apply { setTextColor(color); gravity = Gravity.CENTER })
            g.addView(txt(money(amount), 16f, true).apply { setTextColor(-1); gravity = Gravity.CENTER })
            gaugeRow.addView(g, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5); marginStart = dp(5) })
        }
        gauge("درآمد", incomes, 0xFF41C88A.toInt()); gauge("هزینه", expenses, 0xFFFF6F91.toInt())
        body.addView(gaugeRow)
        val totalButton = btn("تمام تراکنش‌ها  ›") { showPage("سوابق") }
        totalButton.setTextColor(-1); totalButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF43205F.toInt())
        body.addView(totalButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8); bottomMargin = dp(10) })

        fun accordion(title: String, color: Int, entries: List<Pair<String, String>>) {
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF242630.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), color) } }
            val head = TextView(this).apply { text = "⌄   $title"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(-1); gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT; setPadding(dp(14), dp(13), dp(14), dp(13)); setBackgroundColor(color); layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val children = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)); visibility = View.GONE }
            entries.forEach { (label, target) ->
                val b = btn(label) { showPage(target) }
                b.textSize = 14f; b.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL; b.setTextColor(0xFFE8E5EF.toInt())
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF30313C.toInt())
                children.addView(b, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(4) })
            }
            head.setOnClickListener { children.visibility = if (children.visibility == View.VISIBLE) View.GONE else View.VISIBLE; head.text = (if (children.visibility == View.VISIBLE) "⌃   " else "⌄   ") + title }
            group.addView(head); group.addView(children)
            body.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
        accordion("میانبر تراکنش‌ها", 0xFFD94C91.toInt(), listOf("ثبت فروش" to "فروش", "ثبت خرید" to "خرید", "ثبت درآمد" to "درآمد", "ثبت هزینه" to "هزینه"))
        accordion("هزینه‌ها", 0xFF8C52C8.toInt(), listOf("ثبت هزینه جدید" to "هزینه", "گزارش هزینه‌ها" to "سود و زیان"))
        accordion("درآمدها", 0xFF269B69.toInt(), listOf("ثبت درآمد جدید" to "درآمد", "گزارش درآمد و سود و زیان" to "سود و زیان"))
        accordion("بانک‌ها", 0xFF7774D8.toInt(), listOf("صندوق و حساب‌های بانکی" to "صندوق و بانک", "ترازنامه" to "ترازنامه", "گزارش‌ها" to "گزارش‌ها"))
        accordion("آلارم‌ها", 0xFF6D5BC6.toInt(), listOf("تنظیم یادآورها و اعلان‌ها" to "آلارم‌ها", "بدهی‌ها و سررسیدها" to "بدهی‌ها"))
        val personalize = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF242630.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF494454.toInt()) } }
        personalize.addView(txt("شخصی‌سازی صفحه اول", 15f, true).apply { setTextColor(-1) })
        personalize.addView(txt("گزارش‌ها، پرتفوی و تنظیمات از منوی پایین در دسترس‌اند.", 12f).apply { setTextColor(0xFFB8B7C5.toInt()) })
        personalize.addView(btn("تنظیمات برنامه") { showPage("تنظیمات") })
        body.addView(personalize)
    }

    private fun scheduleDailyReminder() {
        try {
            val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, ReminderReceiver::class.java).setAction("ir.behzad.accounting.DAILY_REMINDER")
            val pi = PendingIntent.getBroadcast(this, 9201, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0); if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1) }
            alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
        } catch (_: Exception) { }
    }

    /** Assign stable, unique internal product codes to legacy products that predate code enforcement. */
    private fun ensureProductCodes(): JSONArray {
        val ps = products
        val used = mutableSetOf<String>()
        var seq = prefs.getInt("product_code_seq", 1).coerceAtLeast(1)
        var changed = false
        for (i in 0 until ps.length()) {
            val p = ps.optJSONObject(i) ?: continue
            val current = p.optString("code").trim()
            if (current.isBlank() || used.contains(current)) {
                var code: String
                do { code = "K-%06d".format(Locale.US, seq++) } while (used.contains(code))
                p.put("code", code); used.add(code); changed = true
            } else used.add(current)
        }
        if (changed) save("products", ps)
        prefs.edit().putInt("product_code_seq", seq).apply()
        return ps
    }

    /** Sequential, auditable document numbers by document type and calendar year. */
    private fun nextInvoiceNumber(type: String): String {
        val prefix = when (type) {
            "فروش" -> "SAL"; "خرید" -> "PUR"
            "برگشت از فروش" -> "SRT"; "برگشت از خرید" -> "PRT"
            else -> "DOC"
        }
        val year = SimpleDateFormat("yyyy", Locale.US).format(Date())
        val key = "invoice_seq_${prefix}_$year"
        var maxExisting = prefs.getInt(key, 0).coerceAtLeast(0)
        val pattern = Regex("BZ-${prefix}-${year}-(\\d{6})")
        val all = records
        for (i in 0 until all.length()) {
            val number = all.optJSONObject(i)?.optString("invoiceNo") ?: continue
            val match = pattern.matchEntire(number) ?: continue
            val n = match.groupValues[1].toIntOrNull() ?: continue
            if (n > maxExisting) maxExisting = n
        }
        val next = maxExisting + 1
        prefs.edit().putInt(key, next).apply()
        return "BZ-$prefix-$year-%06d".format(Locale.US, next)
    }

    private fun inventory(body: LinearLayout) {
        body.addView(txt("کالا و انبار", 21f, true))
        body.addView(btn("＋ افزودن کالا") { productDialog(-1) })
        val ps=ensureProductCodes()
        if (ps.length()==0) body.addView(txt("هنوز کالایی تعریف نشده است."))
        for (i in 0 until ps.length()) {
            val p=ps.optJSONObject(i) ?: continue
            val stock=p.optDouble("stock").toLong()
            card(body, "${p.optString("name")}  |  کد: ${p.optString("code","—")}",
                "موجودی: $stock  |  خرید: ${money(p.optLong("buy"))}  |  فروش: ${money(p.optLong("sell"))}") {
                AlertDialog.Builder(this).setTitle(p.optString("name"))
                    .setItems(arrayOf("ویرایش کالا","ورود/خروج موجودی","حذف کالا","لغو")) { _, which ->
                        when(which) {
                            0 -> productDialog(i)
                            1 -> stockDialog(i)
                            2 -> {
                                val code=p.optString("code").trim()
                                val usedInHistory=(0 until records.length()).any { ri ->
                                    val r=records.optJSONObject(ri) ?: return@any false
                                    val items=r.optJSONArray("items") ?: return@any false
                                    (0 until items.length()).any { ii -> items.optJSONObject(ii)?.optString("code")?.trim()==code }
                                }
                                if (usedInHistory || p.optLong("stock") != 0L) {
                                    AlertDialog.Builder(this).setMessage("این کالا سابقه گردش یا موجودی غیرصفر دارد؛ برای حفظ سوابق حسابداری حذف نمی‌شود. آن را غیرفعال کنید یا موجودی را با سند اصلاح انبار به صفر برسانید.")
                                        .setPositiveButton("متوجه شدم",null).show()
                                } else AlertDialog.Builder(this).setMessage("کالا حذف شود؟")
                                    .setPositiveButton("حذف") { _,_ -> val a=products; a.remove(i); save("products",a); showPage("کالا و انبار") }
                                    .setNegativeButton("لغو",null).show()
                            }
                        }
                    }.show()
            }
        }
    }

    private fun productDialog(index: Int) {
        val old=if(index>=0) products.optJSONObject(index) else null
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(5),dp(20),0) }
        val name=edit("نام کالا").also { if(old!=null) it.setText(old.optString("name")) }
        val code=edit("کد کالا یا بارکد").also { if(old!=null) it.setText(old.optString("code")) }
        val buy=edit("قیمت خرید ($currency)",true).also { if(old!=null) it.setText(old.optLong("buy").toString()) }
        val sell=edit("قیمت فروش ($currency)",true).also { if(old!=null) it.setText(old.optLong("sell").toString()) }
        val stock=edit(if(old==null) "موجودی اولیه" else "موجودی فعلی (برای تغییر از ورود/خروج انبار استفاده کنید)",true)
            .also { it.setText(if(old!=null) old.optLong("stock").toString() else "0"); if(old!=null) it.isEnabled=false }
        listOf(name,code,buy,sell,stock).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle(if(old==null) "افزودن کالا" else "ویرایش کالا").setView(box)
            .setPositiveButton("ذخیره") { _,_ ->
                if(name.text.toString().isBlank()) { toast("نام کالا الزامی است"); return@setPositiveButton }
                val buyValue=buy.text.toString().toLongOrNull() ?: -1L
                val sellValue=sell.text.toString().toLongOrNull() ?: -1L
                val opening=stock.text.toString().toLongOrNull() ?: -1L
                if(buyValue<0 || sellValue<0 || opening<0) { toast("قیمت و موجودی باید عدد معتبر و غیرمنفی باشند"); return@setPositiveButton }
                var normalizedCode=code.text.toString().trim()
                val a=products
                if (normalizedCode.isBlank()) {
                    var seq=prefs.getInt("product_code_seq",1).coerceAtLeast(1)
                    val existingCodes=(0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("code")?.trim() }.toSet()
                    do { normalizedCode="K-%06d".format(Locale.US,seq++) } while(existingCodes.contains(normalizedCode))
                    prefs.edit().putInt("product_code_seq",seq).apply()
                    code.setText(normalizedCode)
                }
                for(i in 0 until a.length()) {
                    if(i==index) continue
                    val existing=a.optJSONObject(i) ?: continue
                    if(normalizedCode.isNotBlank() && existing.optString("code").trim()==normalizedCode) {
                        toast("کد کالا تکراری است؛ برای هر کالا کد یکتا وارد کنید"); return@setPositiveButton
                    }
                }
                val p=JSONObject().put("name",name.text.toString().trim()).put("code",normalizedCode)
                    .put("buy",buyValue).put("sell",sellValue)
                    .put("avgCost",if(old!=null) old.optLong("avgCost",old.optLong("buy")).coerceAtLeast(0L) else buyValue)
                    .put("stock",if(old!=null) old.optLong("stock") else opening)
                if(index>=0) a.put(index,p) else a.put(p)
                save("products",a)
                if(old==null && opening>0) {
                    val rs=records
                    rs.put(JSONObject().put("type","موجودی اول دوره").put("title",p.optString("name"))
                        .put("productCode",normalizedCode).put("qty",opening).put("amount",Math.multiplyExact(opening, p.optLong("avgCost").coerceAtLeast(0L)))
                        .put("date",now()).put("currency","عدد").put("note","موجودی اولیه هنگام تعریف کالا؛ ارزش‌گذاری بر مبنای بهای خرید ثبت‌شده"))
                    save("records",rs)
                    val openingValue = Math.multiplyExact(opening, p.optLong("avgCost").coerceAtLeast(0L))
                    if (openingValue > 0L) {
                        try {
                            SharedLedger(prefs).post("موجودی-افتتاحیه", "STOCK-OPEN-${normalizedCode.ifBlank { p.optString("name").hashCode().toString() }}", now(), "موجودی اول دوره: ${p.optString("name")}", listOf(
                                JSONObject().put("accountId", "ACC-" + "موجودی کالا".trim().lowercase().hashCode().toUInt().toString(16)).put("accountName", "موجودی کالا").put("accountClass", "ASSET").put("debit", openingValue).put("credit", 0L),
                                JSONObject().put("accountId", "ACC-" + "طرف مقابل مانده افتتاحیه".trim().lowercase().hashCode().toUInt().toString(16)).put("accountName", "طرف مقابل مانده افتتاحیه").put("accountClass", "EQUITY").put("debit", 0L).put("credit", openingValue)
                            ))
                        } catch (e: Exception) { toast("موجودی ذخیره شد اما ثبت حسابداری افتتاحیه انجام نشد: ${e.message ?: "خطا"}") }
                    }
                }
                showPage("کالا و انبار")
            }.setNegativeButton("لغو",null).show()
    }

    private fun stockDialog(index: Int) {
        val p=products.optJSONObject(index) ?: return
        val amount=edit("تعداد (مثبت وارد کنید)",true)
        val choices=arrayOf("ورود به انبار","خروج از انبار")
        var chosen=0
        AlertDialog.Builder(this).setTitle("تغییر موجودی: ${p.optString("name")}").setView(amount)
            .setSingleChoiceItems(choices,0) { _,w -> chosen=w }
            .setPositiveButton("ثبت") { _,_ ->
                val n=amount.text.toString().toLongOrNull() ?: 0
                if(n<=0) { toast("تعداد معتبر وارد کنید"); return@setPositiveButton }
                val current=p.optLong("stock")
                if(chosen==1 && n>current) { toast("موجودی کافی نیست"); return@setPositiveButton }
                if(chosen==0 && current > Long.MAX_VALUE-n) { toast("تعداد از محدوده مجاز بیشتر است"); return@setPositiveButton }
                p.put("stock", if(chosen==0) current+n else current-n)
                save("products",products)
                val rs=records
                rs.put(JSONObject().put("type",if(chosen==0)"ورود انبار" else "خروج انبار")
                    .put("title",p.optString("name")).put("productCode",p.optString("code"))
                    .put("qty",n).put("amount",n).put("date",now()).put("currency","عدد")
                    .put("note","گردش دستی انبار"))
                save("records",rs); showPage("کالا و انبار")
            }.setNegativeButton("لغو",null).show()
    }

    private data class InvoiceLine(
        val spinner: Spinner,
        val qty: EditText,
        val price: EditText,
        val container: LinearLayout
    )

    private fun invoiceForm(body: LinearLayout, type: String) {
        body.addView(txt("ثبت فاکتور چندقلمی $type",21f,true))
        val isSalesReturn = type == "برگشت از فروش"
        val isPurchaseReturn = type == "برگشت از خرید"
        val isReturn = isSalesReturn || isPurchaseReturn
        val baseType = when (type) { "برگشت از فروش" -> "فروش"; "برگشت از خرید" -> "خرید"; else -> type }
        body.addView(txt(when(type) { "برگشت از فروش" -> "مرجوعی مشتری: موجودی افزایش می‌یابد و فروش خالص کاهش می‌یابد."; "برگشت از خرید" -> "مرجوعی به تأمین‌کننده: موجودی کاهش می‌یابد و خرید خالص کاهش می‌یابد."; else -> "" }, 12f))
        val customer=edit("نام مشتری / تأمین‌کننده")
        val desc=edit("شرح فاکتور (اختیاری)")
        val referenceInvoice=edit("شماره فاکتور اصلی (برای مرجوعی الزامی)")
        if (isReturn) body.addView(referenceInvoice)
        val payment = Spinner(this).apply { adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("نقدی","بانکی","چک","نسیه")) }
        val accountSpinner = Spinner(this)
        fun refreshAccounts() {
            val ac=accounts
            val names=(0 until ac.length()).mapNotNull { ac.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
            accountSpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item, if(names.isEmpty()) listOf("صندوق پیش‌فرض") else names)
        }
        refreshAccounts()
        val checkDue = edit("سررسید چک YYYY-MM-DD (فقط پرداخت با چک)")
        body.addView(customer); body.addView(desc)
        body.addView(txt("روش تسویه",14f,true)); body.addView(payment)
        body.addView(txt("حساب صندوق/بانک (برای تسویه نقدی یا بانکی)",14f,true)); body.addView(accountSpinner)
        body.addView(checkDue)
        val linesBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val lines= mutableListOf<InvoiceLine>()
        body.addView(linesBox)
        fun addLine(preselectCode: String? = null) {
            val ps=ensureProductCodes()
            if(ps.length()==0) { toast("ابتدا کالا را در بخش کالا و انبار تعریف کنید"); return }
            val names=(0 until ps.length()).map { ps.optJSONObject(it)?.optString("name") ?: "" }
            val panel=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                setPadding(dp(10),dp(6),dp(10),dp(6))
                setBackgroundColor(0xFFFFFFFF.toInt())
            }
            panel.addView(txt("قلم کالا ${lines.size+1}",14f,true))
            val sp=Spinner(this)
            sp.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,names)
            val qty=edit("تعداد",true).apply { setText("1") }
            val price=edit("قیمت واحد ($currency)",true)
            panel.addView(sp)
            panel.addView(qty)
            panel.addView(price)
            val idx=if(preselectCode!=null) (0 until ps.length()).firstOrNull { ps.optJSONObject(it)?.optString("code")==preselectCode } else null
            if(idx!=null) sp.setSelection(idx)
            fun fillPrice(position:Int) {
                val prod=products.optJSONObject(position) ?: return
                price.setText((if(baseType=="فروش") prod.optLong("sell") else prod.optLong("buy")).toString())
            }
            sp.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { fillPrice(position) }
            }
            val rowActions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            rowActions.addView(btn("اسکن بارکد") {
                scanBarcode { scanned ->
                    val all=products
                    val found=(0 until all.length()).firstOrNull { all.optJSONObject(it)?.optString("code")==scanned }
                    if(found==null) toast("کالایی با این بارکد پیدا نشد")
                    else { sp.setSelection(found); toast("کالا انتخاب شد") }
                }
            },LinearLayout.LayoutParams(0,-2,1f))
            rowActions.addView(btn("حذف قلم") {
                lines.removeAll { it.container===panel }
                linesBox.removeView(panel)
            },LinearLayout.LayoutParams(0,-2,1f))
            panel.addView(rowActions)
            val lp=LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(8)
            linesBox.addView(panel,lp)
            lines.add(InvoiceLine(sp,qty,price,panel))
        }
        body.addView(btn("＋ افزودن قلم کالا") { addLine() })
        body.addView(btn("＋ افزودن کالا با اسکن بارکد") {
            scanBarcode { scanned ->
                val all=products
                val found=(0 until all.length()).firstOrNull { all.optJSONObject(it)?.optString("code")==scanned }
                if(found==null) toast("کالایی با این بارکد پیدا نشد") else addLine(scanned)
            }
        })
        addLine()
        body.addView(btn("ثبت فاکتور $type") {
            if(lines.isEmpty()) { toast("حداقل یک قلم کالا اضافه کنید"); return@btn }
            val productsNow=ensureProductCodes()
            val allRecords=records
            var sourceInvoice: JSONObject? = null
            val sourceItemsByCode=linkedMapOf<String,JSONObject>()
            val originalQtyByCode=linkedMapOf<String,Long>()
            val alreadyReturnedByCode=linkedMapOf<String,Long>()
            if(isReturn) {
                val ref=referenceInvoice.text.toString().trim()
                if(ref.isBlank()) { toast("شماره فاکتور مرجع برای مرجوعی الزامی است"); return@btn }
                for(i in 0 until allRecords.length()) {
                    val candidate=allRecords.optJSONObject(i) ?: continue
                    if(candidate.optString("invoiceNo")==ref || candidate.optString("id")==ref) {
                        sourceInvoice=candidate; break
                    }
                }
                val expectedType=if(isSalesReturn) "فروش" else "خرید"
                if(sourceInvoice==null || sourceInvoice!!.optString("type")!=expectedType) {
                    toast("فاکتور مرجع معتبر از نوع $expectedType پیدا نشد"); return@btn
                }
                val source=sourceInvoice!!
                if(customer.text.toString().trim().isNotBlank() && source.optString("party").trim().isNotBlank() &&
                    customer.text.toString().trim()!=source.optString("party").trim()) {
                    toast("طرف حساب با فاکتور اصلی یکسان نیست"); return@btn
                }
                val originalItems=source.optJSONArray("items") ?: JSONArray()
                for(i in 0 until originalItems.length()) {
                    val item=originalItems.optJSONObject(i) ?: continue
                    val code=item.optString("code").trim()
                    if(code.isBlank()) { toast("فاکتور مرجع قدیمی است و کد کالا ندارد؛ مرجوعی خودکار آن قابل تأیید نیست"); return@btn }
                    val qty=item.optLong("qty").coerceAtLeast(0L)
                    val priorQty=originalQtyByCode[code] ?: 0L
                    if (priorQty > Long.MAX_VALUE - qty) { toast("تعداد فاکتور مرجع خارج از محدوده مجاز است"); return@btn }
                    val newQty=priorQty+qty
                    val priorItem=sourceItemsByCode[code]
                    if (priorItem==null) {
                        sourceItemsByCode[code]=JSONObject(item.toString())
                    } else {
                        val oldPriceValue=priorQty * priorItem.optLong("unitPrice").coerceAtLeast(0L)
                        val newPriceValue=qty * item.optLong("unitPrice").coerceAtLeast(0L)
                        val oldCostValue=priorQty * priorItem.optLong("unitCost",priorItem.optLong("unitPrice")).coerceAtLeast(0L)
                        val newCostValue=qty * item.optLong("unitCost",item.optLong("unitPrice")).coerceAtLeast(0L)
                        priorItem.put("unitPrice", if(newQty>0) (oldPriceValue+newPriceValue)/newQty else 0L)
                        priorItem.put("unitCost", if(newQty>0) (oldCostValue+newCostValue)/newQty else 0L)
                        priorItem.put("qty",newQty)
                    }
                    originalQtyByCode[code]=newQty
                }
                val sourceId=source.optString("invoiceNo").ifBlank { source.optString("id") }
                for(i in 0 until allRecords.length()) {
                    val prior=allRecords.optJSONObject(i) ?: continue
                    if(prior.optString("referenceInvoice")!=sourceId && prior.optString("referenceInvoice")!=source.optString("id")) continue
                    if(prior.optString("type")!=type) continue
                    val priorItems=prior.optJSONArray("items") ?: JSONArray()
                    for(j in 0 until priorItems.length()) {
                        val item=priorItems.optJSONObject(j) ?: continue
                        val code=item.optString("code").trim()
                        alreadyReturnedByCode[code]=(alreadyReturnedByCode[code] ?: 0L)+item.optLong("qty").coerceAtLeast(0L)
                    }
                }
            }
            val requestedByCode=linkedMapOf<String,Long>()
            val builtLines=JSONArray()
            var total=0L
            val changes= mutableListOf<Pair<JSONObject,Long>>()
            for(line in lines) {
                val index=line.spinner.selectedItemPosition
                val product=productsNow.optJSONObject(index)
                if(product==null) { toast("کالای انتخاب‌شده معتبر نیست"); return@btn }
                val count=line.qty.text.toString().toLongOrNull() ?: 0L
                val unit=line.price.text.toString().toLongOrNull() ?: 0L
                if(count<=0 || unit<0) { toast("تعداد و قیمت هر قلم را بررسی کنید"); return@btn }
                val code=product.optString("code").trim()
                if(code.isBlank()) { toast("برای کالا ابتدا کد یکتا تعریف کنید"); return@btn }
                val stock=product.optLong("stock")
                val requested=(requestedByCode[code] ?: 0L)
                if(requested > Long.MAX_VALUE-count) { toast("تعداد کالا بیش از حد مجاز است"); return@btn }
                requestedByCode[code]=requested+count
                if(type=="فروش" || isPurchaseReturn) {
                    if(requestedByCode[code]!! > stock) { toast("موجودی ${product.optString("name")} با جمع تعداد ردیف‌ها کافی نیست"); return@btn }
                }
                var unitCost=if(type=="خرید") unit else product.optLong("avgCost",product.optLong("buy")).coerceAtLeast(0L)
                if(isReturn) {
                    val sourceItem=sourceItemsByCode[code]
                    if(sourceItem==null) { toast("کالای ${product.optString("name")} در فاکتور مرجع وجود ندارد"); return@btn }
                    val eligible=originalQtyByCode[code] ?: 0L
                    val returned=(alreadyReturnedByCode[code] ?: 0L)+requestedByCode[code]!!
                    if(returned>eligible) { toast("تعداد مرجوعی ${product.optString("name")} از تعداد فاکتور اصلی بیشتر می‌شود"); return@btn }
                    val originalUnitPrice=sourceItem.optLong("unitPrice").coerceAtLeast(0L)
                    if(unit!=originalUnitPrice) { toast("قیمت مرجوعی باید مطابق قیمت واحد فاکتور اصلی باشد"); return@btn }
                    unitCost=sourceItem.optLong("unitCost",sourceItem.optLong("unitPrice")).coerceAtLeast(0L)
                }
                if(count>0 && unit>Long.MAX_VALUE/count) { toast("مبلغ این ردیف بیش از محدوده مجاز است"); return@btn }
                val lineTotal=count*unit
                if(total>Long.MAX_VALUE-lineTotal) { toast("جمع فاکتور بیش از محدوده مجاز است"); return@btn }
                total += lineTotal
                builtLines.put(JSONObject()
                    .put("product",product.optString("name"))
                    .put("code",code)
                    .put("qty",count).put("unitPrice",unit)
                    .put("unitCost",unitCost)
                    .put("lineTotal",lineTotal))
                changes.add(product to count)
            }
            if(total<=0) { toast("مبلغ فاکتور باید بیشتر از صفر باشد"); return@btn }
            val method = payment.selectedItem?.toString() ?: "نقدی"
            if (method == "چک" && !checkDue.text.toString().trim().matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                toast("برای چک، سررسید را با قالب YYYY-MM-DD وارد کنید"); return@btn
            }
            // Apply stock movements only after all lines have passed validation.
            // Update quantity and perpetual weighted-average cost only after every line validates.
            val lineItemsForStock=builtLines
            for(i in 0 until lineItemsForStock.length()) {
                val item=lineItemsForStock.optJSONObject(i) ?: continue
                val code=item.optString("code")
                val product=(0 until productsNow.length()).mapNotNull { productsNow.optJSONObject(it) }
                    .firstOrNull { it.optString("code").trim()==code } ?: continue
                val count=item.optLong("qty").coerceAtLeast(0L)
                val oldQty=product.optLong("stock").coerceAtLeast(0L)
                val oldCost=product.optLong("avgCost",product.optLong("buy")).coerceAtLeast(0L)
                if((type=="خرید" || type=="برگشت از فروش") && oldQty>Long.MAX_VALUE-count) {
                    toast("موجودی ${product.optString("name")} از محدوده مجاز بیشتر می‌شود"); return@btn
                }
                when(type) {
                    "خرید" -> {
                        val purchaseCost=item.optLong("unitPrice").coerceAtLeast(0L)
                        if(oldQty+count>0 && oldQty <= Long.MAX_VALUE-count) {
                            val oldValue=if(oldQty>0 && oldCost>Long.MAX_VALUE/oldQty) Long.MAX_VALUE else oldQty*oldCost
                            val newValue=if(count>0 && purchaseCost>Long.MAX_VALUE/count) Long.MAX_VALUE else count*purchaseCost
                            val combined=if(oldValue>Long.MAX_VALUE-newValue) Long.MAX_VALUE else oldValue+newValue
                            product.put("avgCost", combined/(oldQty+count))
                        }
                        product.put("stock",oldQty+count)
                    }
                    "فروش", "برگشت از خرید" -> product.put("stock",oldQty-count)
                    "برگشت از فروش" -> {
                        val returnedCost=item.optLong("unitCost",oldCost).coerceAtLeast(0L)
                        val newQty=oldQty+count
                        val oldValue=if(oldQty>0 && oldCost>Long.MAX_VALUE/oldQty) Long.MAX_VALUE else oldQty*oldCost
                        val returnValue=if(count>0 && returnedCost>Long.MAX_VALUE/count) Long.MAX_VALUE else count*returnedCost
                        val combined=if(oldValue>Long.MAX_VALUE-returnValue) Long.MAX_VALUE else oldValue+returnValue
                        if(newQty>0) product.put("avgCost",combined/newQty)
                        product.put("stock",newQty)
                    }
                }
            }
            // Do not persist stock changes until the matching journal entry has posted successfully.
            val invoiceNo = nextInvoiceNumber(type)
            val canonicalReference=if(isReturn) sourceInvoice?.optString("invoiceNo").orEmpty().ifBlank { sourceInvoice?.optString("id").orEmpty() } else ""
            val partyName=customer.text.toString().trim().ifBlank { if(isReturn) sourceInvoice?.optString("party").orEmpty() else "" }
            val r=JSONObject().put("id", invoiceNo).put("invoiceNo", invoiceNo)
                .put("referenceInvoice", canonicalReference)
                .put("type",type).put("party",partyName)
                .put("title",desc.text.toString().ifBlank { "فاکتور $type" })
                .put("amount",total).put("total",total).put("items",builtLines)
                .put("isReturn",isReturn).put("baseType",baseType)
                .put("paymentMethod",method).put("accountName",accountSpinner.selectedItem?.toString() ?: "صندوق پیش‌فرض")
                .put("date",now()).put("currency",currency)
            try {
                SharedLedger(prefs).postInvoice(r)
            } catch (e: Exception) {
                toast("ثبت در دفتر روزنامه انجام نشد: ${e.message ?: "خطای حسابداری"}")
                return@btn
            }
            // Commit the inventory snapshot only after the journal accepted this invoice.
            save("products",productsNow)
            records.put(r); save("records",records)
            if (method == "نقدی" || method == "بانکی") {
                val ac=accounts
                val selectedName=accountSpinner.selectedItem?.toString() ?: "صندوق پیش‌فرض"
                var found=false
                for (i in 0 until ac.length()) {
                    val account=ac.optJSONObject(i) ?: continue
                    if(account.optString("name")==selectedName) {
                        val oldBalance=account.optLong("balance")
                        account.put("balance", when(type) { "فروش", "برگشت از خرید" -> oldBalance+total; "خرید", "برگشت از فروش" -> oldBalance-total; else -> oldBalance })
                        ac.put(i,account); found=true; break
                    }
                }
                if(found) save("accounts",ac) else {
                    ac.put(JSONObject().put("name",selectedName).put("kind",if(method=="بانکی") "بانک" else "صندوق").put("balance",if(type=="فروش" || type=="برگشت از خرید") total else -total))
                    save("accounts",ac)
                }
            } else if (method == "چک") {
                val checks=arr("checks")
                checks.put(JSONObject().put("direction",if(type=="فروش" || type=="برگشت از خرید") "دریافتی" else "پرداختی")
                    .put("party",partyName).put("title",desc.text.toString().ifBlank { "چک فاکتور $type" })
                    .put("amount",total).put("dueDate",checkDue.text.toString().trim()).put("status","باز")
                    .put("recordDate",now()).put("recordType",type).put("currency",currency)
                    .put("sourceRecordId", r.optString("id", "${now()}-$total")))
                save("checks",checks)
            } else if (method == "نسیه") {
                val debts=arr("debts")
                debts.put(JSONObject().put("person",partyName.ifBlank { "طرف حساب نامشخص" })
                    .put("title",desc.text.toString().ifBlank { "فاکتور نسیه $type" }).put("amount",total)
                    .put("kind",when(type) { "فروش" -> "طلب من"; "خرید" -> "بدهی من"; "برگشت از فروش" -> "کاهش طلب من"; else -> "کاهش بدهی من" })
                    .put("status","باز").put("date",now()).put("dueDate", "").put("recordType",type))
                save("debts",debts)
            }
            if(partyName.isNotBlank()) savePersonIfMissing(partyName,if(baseType=="فروش")"مشتری" else "تأمین‌کننده")
            toast("فاکتور $type ثبت شد")
            showInvoicePreview(r)
            showPage("خانه")
        })
        body.addView(txt("جمع فاکتور از تعداد و قیمت هر قلم محاسبه می‌شود. برای ثبت بارکد، ابتدا کد کالا را در اطلاعات کالا ذخیره کنید.",12f))
    }

    private fun scanBarcode(onScanned: (String) -> Unit) {
        try {
            val options=GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(this,options).startScan()
                .addOnSuccessListener { barcode ->
                    val value=barcode.rawValue
                    if(!value.isNullOrBlank()) onScanned(value) else toast("بارکد خوانده نشد")
                }
                .addOnCanceledListener { }
                .addOnFailureListener { toast("اسکن بارکد انجام نشد؛ سرویس Google Play را بررسی کنید") }
        } catch (_: Exception) { toast("اسکنر بارکد در این دستگاه در دسترس نیست") }
    }

    private fun showInvoicePreview(r: JSONObject) {
        val items=r.optJSONArray("items") ?: JSONArray()
        val rows=StringBuilder()
        for(i in 0 until items.length()) {
            val item=items.optJSONObject(i) ?: continue
            rows.append("<tr><td>${item.optString("product")}</td><td>${item.optLong("qty")}</td><td>${money(item.optLong("unitPrice"))}</td><td>${money(item.optLong("lineTotal"))}</td></tr>")
        }
        val html="""<html><meta charset="utf-8"><body dir="rtl" style="font-family:sans-serif;padding:24px">
        <h2 style="text-align:center">حسابداری بهزاد</h2><h3>فاکتور ${r.optString("type")}</h3>
        <p>تاریخ: ${r.optString("date")}</p><p>طرف حساب: ${r.optString("party")}</p>
        <table border="1" cellspacing="0" cellpadding="8" width="100%"><tr><th>کالا</th><th>تعداد</th><th>قیمت واحد</th><th>جمع</th></tr>$rows</table>
        <h3>مبلغ کل: ${money(r.optLong("total"))}</h3><hr><p>حسابداری بهزاد</p></body></html>"""
        AlertDialog.Builder(this).setTitle("فاکتور ثبت شد")
            .setMessage("مبلغ کل: ${money(r.optLong("total"))}\nطرف حساب: ${r.optString("party")}\nتعداد اقلام: ${items.length()}")
            .setPositiveButton("چاپ فاکتور") { _,_ -> printHtml(html) }
            .setNegativeButton("بستن",null).show()
    }

    private fun printHtml(html: String) {
        try {
            val web=WebView(this)
            web.loadDataWithBaseURL(null,html,"text/HTML","UTF-8",null)
            web.postDelayed({
                val manager=getSystemService(PRINT_SERVICE) as PrintManager
                manager.print("فاکتور-حسابداری-بهزاد",web.createPrintDocumentAdapter("فاکتور"),PrintAttributes.Builder().build())
            },800)
        } catch (_: Exception) { toast("امکان چاپ در این دستگاه فراهم نشد") }
    }

    private fun simpleTransaction(body: LinearLayout, type: String) {
        body.addView(txt("ثبت $type",21f,true))
        val title=edit("شرح $type")
        val amount=edit("مبلغ به $currency",true)
        body.addView(title); body.addView(amount)
        body.addView(btn("ذخیره $type") {
            val n=amount.text.toString().toLongOrNull()
            if(n==null || n<=0) { toast("مبلغ معتبر وارد کنید"); return@btn }
            val entry = JSONObject().put("id", "TX-${System.currentTimeMillis()}").put("type",type)
                .put("title",title.text.toString().ifBlank{type}).put("amount",n).put("date",now()).put("currency",currency)
            try { SharedLedger(prefs).postSimple(type, entry.optString("id"), entry.optString("date"), entry.optString("title"), n) }
            catch (e: Exception) { toast("ثبت در دفتر روزنامه انجام نشد: ${e.message ?: "خطای حسابداری"}"); return@btn }
            val a=records; a.put(entry); save("records",a); toast("$type ثبت شد و به دفتر مشترک پیوست"); showPage("خانه")
        })
    }

    private fun peoplePage(body: LinearLayout) {
        body.addView(txt("مشتریان و تأمین‌کنندگان",21f,true))
        body.addView(btn("＋ افزودن شخص") { personDialog(-1) })
        val a=people
        if(a.length()==0) body.addView(txt("هنوز شخصی ثبت نشده است."))
        for(i in 0 until a.length()) {
            val p=a.optJSONObject(i) ?: continue
            card(body,"${p.optString("name")} — ${p.optString("kind")}",p.optString("phone","")) {
                AlertDialog.Builder(this).setTitle(p.optString("name"))
                    .setItems(arrayOf("ویرایش","حذف","لغو")) { _,w ->
                        when(w) {
                            0 -> personDialog(i)
                            1 -> { val n=people; n.remove(i); save("people",n); showPage("اشخاص") }
                        }
                    }.show()
            }
        }
    }
    private fun personDialog(index:Int) {
        val old=if(index>=0) people.optJSONObject(index) else null
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),0,dp(18),0) }
        val name=edit("نام شخص").also { if(old!=null) it.setText(old.optString("name")) }
        val phone=edit("تلفن (اختیاری)").also { if(old!=null) it.setText(old.optString("phone")) }
        val kinds=arrayOf("مشتری","تأمین‌کننده","بدهکار","بستانکار","عضو خانواده")
        val sp=Spinner(this); sp.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,kinds)
        if(old!=null) sp.setSelection(kinds.indexOf(old.optString("kind")).coerceAtLeast(0))
        box.addView(name); box.addView(phone); box.addView(sp)
        AlertDialog.Builder(this).setTitle("اطلاعات شخص").setView(box).setPositiveButton("ذخیره") { _,_ ->
            if(name.text.isBlank()) { toast("نام را وارد کنید"); return@setPositiveButton }
            val p=JSONObject().put("name",name.text.toString()).put("phone",phone.text.toString()).put("kind",sp.selectedItem.toString())
            val a=people; if(index>=0) a.put(index,p) else a.put(p); save("people",a); showPage("اشخاص")
        }.setNegativeButton("لغو",null).show()
    }
    private fun savePersonIfMissing(name:String,kind:String) {
        val a=people
        for(i in 0 until a.length()) if(a.optJSONObject(i)?.optString("name")==name) return
        a.put(JSONObject().put("name",name).put("kind",kind).put("phone","")); save("people",a)
    }

    private fun accountsPage(body: LinearLayout) {
        body.addView(txt("صندوق و حساب‌های بانکی",21f,true))
        body.addView(btn("＋ افزودن صندوق یا بانک") { accountDialog(-1) })
        val a=accounts
        if(a.length()==0) body.addView(txt("حسابی تعریف نشده است."))
        for(i in 0 until a.length()) {
            val x=a.optJSONObject(i) ?: continue
            card(body,x.optString("name")+" — "+x.optString("kind"),"مانده فعلی: ${money(x.optLong("balance"))}") {
                accountDialog(i)
            }
        }
    }
    private fun accountDialog(index:Int) {
        val old=if(index>=0) accounts.optJSONObject(index) else null
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),0,dp(18),0) }
        val name=edit("نام صندوق یا بانک").also { if(old!=null) it.setText(old.optString("name")) }
        val balance=edit(if(old==null) "مانده اولیه ($currency) — صفر هم مجاز است" else "مانده فعلی (برای ویرایش تغییر نمی‌کند)",true).also { if(old!=null) it.setText(old.optLong("balance").toString()) }
        val sp=Spinner(this); sp.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("صندوق نقدی","حساب بانکی","کیف پول"))
        if(old!=null) sp.setSelection(arrayOf("صندوق نقدی","حساب بانکی","کیف پول").indexOf(old.optString("kind")).coerceAtLeast(0))
        box.addView(name); box.addView(balance); box.addView(sp)
        if (old == null) box.addView(txt("راهنما: ماندهٔ اولیه فقط یک‌بار وارد شود. اگر مانده‌ها را در سند افتتاحیه هم وارد می‌کنید، همان مبلغ را دوباره به‌عنوان ماندهٔ حساب ثبت نکنید. طرف مقابل این مانده باید در حسابداری افتتاحیه مشخص و مستند شود.", 12f))
        AlertDialog.Builder(this).setTitle("حساب مالی").setView(box).setPositiveButton("ذخیره") { _,_ ->
            if(name.text.isBlank()) { toast("نام حساب الزامی است"); return@setPositiveButton }
            val a=accounts
            val rawBalance=balance.text.toString().trim()
            val enteredBalance=if(old==null && rawBalance.isBlank()) 0L else rawBalance.toLongOrNull()
            if (old == null && (enteredBalance == null || enteredBalance < 0L)) { toast("مانده اولیه باید عدد صحیح و غیرمنفی باشد"); return@setPositiveButton }
            // Editing an existing account changes its label/type only; it must not erase its running balance.
            val x=if(old!=null) JSONObject(old.toString()).put("name",name.text.toString().trim()).put("kind",sp.selectedItem.toString())
                  else JSONObject().put("id","ACC-${System.currentTimeMillis()}").put("name",name.text.toString().trim()).put("balance",enteredBalance ?: 0L).put("openingBalance",enteredBalance ?: 0L).put("kind",sp.selectedItem.toString())
            if (old == null && (enteredBalance ?: 0L) > 0L) {
                val openingId = x.optString("id")
                val accountName = x.optString("name")
                val openingAmount = enteredBalance ?: 0L
                try {
                    SharedLedger(prefs).post("مانده-افتتاحیه-صندوق-بانک", openingId, now(), "مانده اولیه حساب $accountName", listOf(
                        JSONObject().put("accountId", "ACC-" + accountName.trim().lowercase().hashCode().toUInt().toString(16)).put("accountName", accountName).put("accountClass", "ASSET").put("debit", openingAmount).put("credit", 0L),
                        JSONObject().put("accountId", "ACC-" + "طرف مقابل مانده افتتاحیه".trim().lowercase().hashCode().toUInt().toString(16)).put("accountName", "طرف مقابل مانده افتتاحیه").put("accountClass", "EQUITY").put("debit", 0L).put("credit", openingAmount)
                    ))
                } catch (e: Exception) { toast("مانده حساب ذخیره نشد؛ ثبت دفتر مشترک ناموفق بود: ${e.message ?: "خطای حسابداری"}"); return@setPositiveButton }
            }
            if(index>=0) a.put(index,x) else a.put(x)
            save("accounts",a); showPage("صندوق و بانک")
        }.setNegativeButton("لغو",null).show()
    }

    /** Records the cash/bank leg when an open check or installment is settled. */
    private fun settleFinancialItem(title: String, amount: Long, cashIn: Boolean, sourceId: String, party: String, onSettled: (String) -> Unit) {
        if (amount <= 0L) { toast("مبلغ مورد معتبر نیست؛ تسویه انجام نشد"); return }
        val a = accounts
        if (a.length() == 0) {
            toast("ابتدا از بخش صندوق و بانک یک حساب تعریف کنید؛ تسویه انجام نشد")
            return
        }
        val labels = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { "${it.optString("name")} — ${it.optString("kind")} (${money(it.optLong("balance"))})" } }.toTypedArray()
        AlertDialog.Builder(this).setTitle("انتخاب حساب برای $title").setItems(labels) { _, index ->
            val account = a.optJSONObject(index) ?: run { toast("حساب انتخاب‌شده معتبر نیست"); return@setItems }
            val movementId = "SET-${System.currentTimeMillis()}"
            val clearing = when {
                title.contains("چک") && cashIn -> "اسناد دریافتنی"
                title.contains("چک") && !cashIn -> "اسناد پرداختنی"
                title.contains("قسط") && cashIn -> "مطالبات اقساطی"
                title.contains("قسط") && !cashIn -> "بدهی اقساطی"
                cashIn -> "مطالبات متفرقه"
                else -> "بدهی‌های متفرقه"
            }
            try { SharedLedger(prefs).postSettlement(movementId, now(), "$title - $party", account.optString("name"), amount, cashIn, clearing) }
            catch (e: Exception) { toast("ثبت تسویه در دفتر مشترک انجام نشد: ${e.message ?: "خطای حسابداری"}"); return@setItems }
            val current = account.optLong("balance")
            val next = if (cashIn) current + amount else current - amount
            account.put("balance", next)
            a.put(index, account); save("accounts", a)
            val movements = arr("settlement_movements")
            movements.put(JSONObject().put("id", movementId)
                .put("title", title).put("sourceId", sourceId).put("party", party)
                .put("accountName", account.optString("name")).put("amount", amount)
                .put("direction", if (cashIn) "دریافت" else "پرداخت").put("date", now()).put("currency", currency))
            save("settlement_movements", movements)
            onSettled(account.optString("name"))
        }.setNegativeButton("انصراف", null).show()
    }

    private fun checksPage(body: LinearLayout) {
        body.addView(txt("چک‌های دریافتی و پرداختی",21f,true))
        val checks=arr("checks")
        if(checks.length()==0) body.addView(txt("چکی ثبت نشده است. چک‌ها هنگام ثبت فاکتور با روش تسویه «چک» ایجاد می‌شوند."))
        for(i in 0 until checks.length()) {
            val c=checks.optJSONObject(i) ?: continue
            card(body,"چک ${c.optString("direction")} — ${c.optString("party")}","${money(c.optLong("amount"))} | سررسید ${c.optString("dueDate")} | ${c.optString("status")}") {
                AlertDialog.Builder(this).setTitle("مدیریت چک").setItems(arrayOf("علامت‌گذاری وصول/پرداخت‌شده","لغو")) { _,choice ->
                    when(choice) {
                        0 -> settleFinancialItem("تسویه چک", c.optLong("amount"), c.optString("direction") == "دریافتی", c.optString("sourceRecordId"), c.optString("party")) {
                            c.put("status","تسویه‌شده"); c.put("settledDate",now()); c.put("settlementAccount",it)
                            save("checks",checks); showPage("چک‌ها")
                        }
                    }
                }.show()
            }
        }
        body.addView(txt("تسویهٔ چک در این نسخه وضعیت چک را تغییر می‌دهد؛ ثبت خودکار سند بانکی هنگام تسویه هنوز نیازمند ثبت حساب و کنترل گردش بانکی است.",12f))
    }

    private fun installmentsPage(body: LinearLayout) {
        body.addView(txt("اقساط دریافتی و پرداختی", 21f, true))
        body.addView(btn("＋ ثبت قسط") { installmentDialog() })
        val a = arr("installments")
        if (a.length() == 0) body.addView(txt("قسطی ثبت نشده است."))
        for (i in 0 until a.length()) {
            val item = a.optJSONObject(i) ?: continue
            card(body, "قسط ${item.optString("direction")} — ${item.optString("party")}",
                "${item.optString("title")} | ${money(item.optLong("amount"))} | سررسید ${item.optString("dueDate")} | ${item.optString("status")}") {
                AlertDialog.Builder(this).setTitle("مدیریت قسط").setItems(arrayOf("علامت‌گذاری تسویه‌شده", "لغو")) { _, choice ->
                    when (choice) {
                        0 -> settleFinancialItem("تسویه قسط", item.optLong("amount"), item.optString("direction") == "قسط دریافتی", item.optString("id"), item.optString("party")) {
                            item.put("status", "تسویه‌شده"); item.put("settledDate", now()); item.put("settlementAccount",it)
                            save("installments", a); showPage("اقساط")
                        }
                    }
                }.show()
            }
        }
        body.addView(txt("ثبت اولیه و تسویه اقساط در دفتر مشترک انجام می‌شود. مانده افتتاحیه طرف مقابل به‌عنوان سرمایه افتتاحیه مستند ثبت شده و باید با مدارک واقعی تطبیق داده شود.", 12f))
    }

    private fun installmentDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
        val party = edit("نام طرف حساب")
        val title = edit("شرح قسط")
        val amount = edit("مبلغ قسط ($currency)", true)
        val due = edit("سررسید YYYY-MM-DD")
        val direction = Spinner(this).apply { adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("قسط دریافتی", "قسط پرداختی")) }
        listOf(party, title, amount, due, direction).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("ثبت قسط").setView(box).setPositiveButton("ذخیره") { _, _ ->
            val n = amount.text.toString().toLongOrNull()
            if (party.text.isBlank() || n == null || n <= 0 || !due.text.toString().trim().matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                toast("نام طرف حساب، مبلغ مثبت و تاریخ معتبر YYYY-MM-DD الزامی است"); return@setPositiveButton
            }
            val a = arr("installments")
            val installmentId = "INS-${System.currentTimeMillis()}"
            val isReceivable = direction.selectedItemPosition == 0
            try {
                SharedLedger(prefs).postDebtStatus(installmentId, now(), "افتتاح قسط: ${title.text.toString().trim()} - ${party.text.toString().trim()}", n,
                    isReceivable, if (isReceivable) "مطالبات اقساطی" else "بدهی اقساطی")
            } catch (e: Exception) { toast("ثبت قسط در دفتر مشترک انجام نشد: ${e.message ?: "خطای حسابداری"}"); return@setPositiveButton }
            a.put(JSONObject().put("id", installmentId).put("party", party.text.toString().trim())
                .put("title", title.text.toString().trim()).put("amount", n)
                .put("direction", direction.selectedItem.toString()).put("dueDate", due.text.toString().trim())
                .put("status", "باز").put("date", now()).put("currency", currency))
            save("installments", a)
            savePersonIfMissing(party.text.toString().trim(), if (direction.selectedItemPosition == 0) "مشتری" else "تأمین‌کننده")
            showPage("اقساط")
        }.setNegativeButton("لغو", null).show()
    }

    private fun debtsPage(body: LinearLayout) {
        body.addView(txt("بدهی‌ها و مطالبات",21f,true))
        body.addView(btn("＋ ثبت بدهی یا طلب") { debtDialog() })
        val a=arr("debts")
        if(a.length()==0) body.addView(txt("موردی ثبت نشده است."))
        for(i in 0 until a.length()) {
            val d=a.optJSONObject(i) ?: continue
            card(body,"${d.optString("kind")} — ${d.optString("person")}", "${d.optString("title")} | ${money(d.optLong("amount"))} | ${d.optString("status")}") {
                AlertDialog.Builder(this).setTitle("وضعیت مورد").setItems(arrayOf("علامت‌گذاری تسویه‌شده","لغو")) { _,w ->
                    when(w) {
                        0 -> {
                            val isReceivable = d.optString("kind") == "طلب من"
                            settleFinancialItem("تسویه بدهی/طلب", d.optLong("amount"), isReceivable, d.optString("id"), d.optString("person")) {
                                d.put("status","تسویه‌شده").put("settledDate", now()).put("settlementAccount", it)
                                save("debts",a); showPage("بدهی‌ها")
                            }
                        }
                    }
                }.show()
            }
        }
    }
    private fun debtDialog() {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),0,dp(18),0) }
        val person=edit("نام طرف حساب"); val title=edit("شرح بدهی یا طلب"); val amount=edit("مبلغ ($currency)",true); val dueDate=edit("سررسید به شکل YYYY-MM-DD (اختیاری)")
        val sp=Spinner(this); sp.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("بدهی من","طلب من"))
        listOf(person,title,amount,dueDate,sp).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("ثبت بدهی/طلب").setView(box).setPositiveButton("ذخیره") { _,_ ->
            val n=amount.text.toString().toLongOrNull()
            if(person.text.isBlank() || n==null || n<=0) { toast("نام و مبلغ معتبر وارد کنید"); return@setPositiveButton }
            val a=arr("debts")
            val debtId = "DEBT-${System.currentTimeMillis()}"
            val isReceivable = sp.selectedItemPosition == 1
            try { SharedLedger(prefs).postDebtStatus(debtId, now(), "افتتاح ${sp.selectedItem} - ${title.text.toString().trim()} - ${person.text.toString().trim()}", n, isReceivable) }
            catch (e: Exception) { toast("ثبت بدهی/طلب در دفتر مشترک انجام نشد: ${e.message ?: "خطای حسابداری"}"); return@setPositiveButton }
            a.put(JSONObject().put("id",debtId).put("person",person.text.toString()).put("title",title.text.toString()).put("amount",n)
                .put("kind",sp.selectedItem.toString()).put("status","باز").put("date",now()).put("dueDate",dueDate.text.toString().trim()))
            save("debts",a); savePersonIfMissing(person.text.toString(),if(sp.selectedItemPosition==0)"بستانکار" else "بدهکار"); showPage("بدهی‌ها")
        }.setNegativeButton("لغو",null).show()
    }

    private fun history(body: LinearLayout) {
        body.addView(txt("سوابق تراکنش‌ها و فاکتورها",21f,true))
        val a=records
        if(a.length()==0) body.addView(txt("هنوز تراکنشی ثبت نشده است."))
        for(i in a.length()-1 downTo 0) {
            val r=a.optJSONObject(i) ?: continue
            val amount=r.optLong("total",r.optLong("amount"))
            card(body,"${r.optString("type")} — ${r.optString("title")} | ${r.optString("date")}",
                "${money(amount)} | ${r.optString("party","")}") {
                AlertDialog.Builder(this).setTitle("جزئیات").setMessage(r.toString(2))
                    .setPositiveButton("چاپ فاکتور") { _,_ -> printHtml("<html><body dir='rtl'><h2>حسابداری بهزاد</h2><pre>${r.toString(2)}</pre></body></html>") }
                    .setNegativeButton("بستن",null).show()
            }
        }
    }

    private fun reports(body: LinearLayout) {
        body.addView(txt("گزارش‌های مالی", 21f, true))
        body.addView(txt("برای دیدن معین، تفصیلی و ریزتراکنش‌ها روی هر گزارش بزنید.", 13f))
        card(body, "سند افتتاحیه", "ثبت مانده‌های ابتدای دوره و کنترل توازن سند") { showPage("سند افتتاحیه") }
        card(body, "ترازنامه دو ستونی", "دارایی‌ها، بدهی‌ها و سرمایه") { showPage("ترازنامه") }
        card(body, "گزارش سود و زیان", "درآمدها، هزینه‌ها و نتیجه دوره") { showPage("سود و زیان") }
        card(body, "گردش حساب‌ها و مانده", "حساب‌های صندوق و بانک") { showPage("صندوق و بانک") }
        card(body, "ریز تراکنش‌ها و فاکتورها", "مشاهده جزئیات هر ثبت") { showPage("سوابق") }
        body.addView(btn("ذخیره گزارش در Excel") { exportExcel() })
        body.addView(btn("ذخیره گزارش در PDF") { exportPdf() })
        body.addView(btn("کنترل تراز دفتر روزنامه مشترک") {
            val t = SharedLedger(prefs).trialBalance()
            AlertDialog.Builder(this).setTitle("کنترل دفتر روزنامه")
                .setMessage("جمع بدهکار: ${money(t.optLong("totalDebit"))}\nجمع بستانکار: ${money(t.optLong("totalCredit"))}\nوضعیت: ${if (t.optBoolean("balanced")) "متوازن" else "نامتوازن"}\n\nاین کنترل فقط ثبت‌های دفتر مشترک را می‌سنجد؛ گزارش‌های قدیمی تا تکمیل مهاجرت همهٔ عملیات از آن جدا هستند.")
                .setPositiveButton("بستن", null).show()
        })
        body.addView(txt("هشدار: دفتر روزنامه مشترک برای ثبت‌های جدید فعال شده است؛ گزارش‌های قدیمی هنوز کاملاً از این دفتر استخراج نمی‌شوند. برای گزارش رسمی، مهاجرت و اتصال کامل تسویه‌ها، چک‌ها، اقساط و مانده‌های قدیمی لازم است.", 12f))
    }

    private fun openingBalancePage(body: LinearLayout) {
        body.addView(txt("سند افتتاحیه", 22f, true))
        body.addView(txt("مانده‌های واقعی ابتدای دوره را وارد کنید. سند فقط وقتی ذخیره می‌شود که جمع بدهکار و بستانکار برابر باشد. از ثبت دوبارهٔ مانده‌هایی که قبلاً در حساب‌ها یا کالاها وارد شده‌اند خودداری کنید.", 13f))
        val docs = arr("opening_documents")
        body.addView(btn("＋ ثبت سند افتتاحیه جدید") { openingBalanceDialog() })
        if (docs.length() == 0) body.addView(txt("هنوز سند افتتاحیه‌ای ثبت نشده است."))
        for (i in docs.length() - 1 downTo 0) {
            val d = docs.optJSONObject(i) ?: continue
            val lines = d.optJSONArray("lines") ?: JSONArray()
            val date = d.optString("date", "بدون تاریخ")
            val debit = d.optLong("debitTotal")
            val credit = d.optLong("creditTotal")
            card(body, "سند ${d.optString("number", "افتتاحیه")} — $date", "بدهکار: ${money(debit)} | بستانکار: ${money(credit)} | ${if (debit == credit) "متوازن" else "نامتوازن"}") {
                val detail = StringBuilder()
                for (j in 0 until lines.length()) {
                    val l = lines.optJSONObject(j) ?: continue
                    detail.append(l.optString("account")).append(" — بدهکار: ").append(money(l.optLong("debit")))
                        .append(" — بستانکار: ").append(money(l.optLong("credit"))).append("\n")
                }
                AlertDialog.Builder(this).setTitle("جزئیات سند افتتاحیه").setMessage(detail.toString()).setPositiveButton("بستن", null).show()
            }
        }
        body.addView(txt("توجه حسابداری: سند افتتاحیه پس از کنترل توازن به دفتر روزنامهٔ مشترک ارسال می‌شود و در ترازنامه اثر می‌گذارد. پیش از ثبت، مانده‌های صندوق، بانک و موجودی کالا را با سوابق قبلی تطبیق دهید تا دوباره‌شماری نشود. سرفصل طرف مقابل باید بر اساس مدارک واقعی تعیین شود؛ از ثبت سرمایهٔ ترازکنندهٔ صوری خودداری کنید.", 12f))
    }

    private fun openingBalanceDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val date = edit("تاریخ ابتدای دوره (مثلاً 1405/01/01)").also { it.setText(now().substringBefore(" ")) }
        box.addView(date)
        // Use actual account names when available instead of forcing every cash/bank balance into one generic line.
        val actualAccountNames = (0 until accounts.length()).mapNotNull { i ->
            accounts.optJSONObject(i)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()
        val labels = (if (actualAccountNames.isNotEmpty()) actualAccountNames else listOf("صندوق و بانک")) +
            listOf("مطالبات اشخاص", "موجودی کالا", "سایر دارایی‌ها", "بدهی به اشخاص", "وام و سایر بدهی‌ها", "سرمایه", "سود/زیان انباشته مستند")
        val rows = mutableListOf<Triple<String, EditText, Spinner>>()
        labels.forEach { label ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val amount = edit("مبلغ $label", true)
            val side = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("بدهکار", "بستانکار")) }
            line.addView(TextView(this).apply { text = label; textSize = 12f }, LinearLayout.LayoutParams(0, -2, 1.1f))
            line.addView(amount, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(side, LinearLayout.LayoutParams(0, -2, 0.8f))
            box.addView(line)
            rows.add(Triple(label, amount, side))
        }
        val scroll = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("ثبت مانده‌های افتتاحیه").setView(scroll).setPositiveButton("کنترل و ذخیره", null).setNegativeButton("لغو", null).create().also { dialog ->
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (date.text.isBlank()) { toast("تاریخ سند الزامی است"); return@setOnClickListener }
                    val lines = JSONArray(); var debit = 0L; var credit = 0L
                    for ((label, field, side) in rows) {
                        val raw = field.text.toString().trim()
                        if (raw.isBlank()) continue
                        val amount = raw.toLongOrNull()
                        if (amount == null || amount < 0L) { toast("مبلغ «$label» باید عدد صحیح و غیرمنفی باشد"); return@setOnClickListener }
                        if (amount == 0L) continue
                        val isDebit = side.selectedItemPosition == 0
                        if (isDebit) debit += amount else credit += amount
                        lines.put(JSONObject().put("account", label).put("debit", if (isDebit) amount else 0L).put("credit", if (isDebit) 0L else amount))
                    }
                    if (lines.length() < 2) { toast("حداقل دو ردیف مبلغ‌دار لازم است"); return@setOnClickListener }
                    if (debit != credit) { toast("سند متوازن نیست. اختلاف: ${money(kotlin.math.abs(debit-credit))}"); return@setOnClickListener }
                    val docs = arr("opening_documents")
                    val id = "OPEN-${System.currentTimeMillis()}"
                    try { SharedLedger(prefs).postOpening(id, date.text.toString().trim(), lines) }
                    catch (e: Exception) { toast("ثبت افتتاحیه در دفتر مشترک انجام نشد: ${e.message ?: "خطای حسابداری"}"); return@setOnClickListener }
                    docs.put(JSONObject().put("id", id).put("number", "افتتاحیه-${docs.length()+1}").put("date", date.text.toString().trim())
                        .put("lines", lines).put("debitTotal", debit).put("creditTotal", credit).put("currency", currency).put("createdAt", now()).put("status", "متوازن"))
                    save("opening_documents", docs)
                    dialog.dismiss(); toast("سند افتتاحیه متوازن ذخیره شد"); showPage("سند افتتاحیه")
                }
            }
            dialog.show()
        }
    }

    private fun balanceSheet(body: LinearLayout) {
        body.addView(txt("ترازنامه از دفتر روزنامه مشترک", 22f, true))
        body.addView(txt("این گزارش فقط از اسناد ثبت‌شده در دفتر مشترک محاسبه می‌شود. سوابقی که هنوز به دفتر منتقل نشده‌اند در این گزارش وارد نمی‌شوند.", 13f))
        val report = SharedLedger(prefs).financialStatement()
        val rows = report.optJSONArray("accounts") ?: JSONArray()
        val assets = mutableListOf<Pair<String, Long>>()
        val liabilities = mutableListOf<Pair<String, Long>>()
        val equity = mutableListOf<Pair<String, Long>>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val name = r.optString("accountName", "حساب")
            val net = r.optLong("net")
            when (r.optString("accountClass")) {
                "ASSET" -> if (net != 0L) assets += name to net
                "LIABILITY" -> if (net != 0L) liabilities += name to -net
                "EQUITY" -> if (net != 0L) equity += name to -net
            }
        }
        val profit = report.optLong("profit")
        if (profit != 0L) equity += "سود/زیان دوره از دفتر مشترک" to profit
        fun row(name: String, debit: Long?, credit: Long?, header: Boolean = false, total: Boolean = false): LinearLayout {
            val bg = if (header || total) 0xFF343434.toInt() else 0xFF292B33.toInt()
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(bg)
                val color = if (header || total) -1 else 0xFFE5E7EB.toInt()
                addView(txt(name, if (header) 13f else 12f, header || total).apply { setTextColor(color); gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT; setPadding(dp(8), dp(6), dp(8), dp(6)) }, LinearLayout.LayoutParams(0, dp(44), 1.45f))
                addView(txt(if (debit == null) "—" else money(debit), 12f, header || total).apply { setTextColor(if (!header && !total) 0xFF35B879.toInt() else color); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(txt(if (credit == null) "—" else money(credit), 12f, header || total).apply { setTextColor(if (!header && !total) 0xFFFF7373.toInt() else color); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
        }
        body.addView(row("حساب", null, null, header = true))
        body.addView(txt("دارایی‌ها", 15f, true))
        for ((name, amount) in assets) body.addView(row(name, if (amount > 0) amount else null, if (amount < 0) -amount else null))
        body.addView(row("جمع خالص دارایی‌ها", report.optLong("assets"), null, total = true))
        body.addView(txt("بدهی‌ها", 15f, true))
        for ((name, amount) in liabilities) body.addView(row(name, if (amount < 0) -amount else null, if (amount > 0) amount else null))
        body.addView(row("جمع بدهی‌ها", null, report.optLong("liabilities"), total = true))
        body.addView(txt("حقوق مالکانه و نتیجه دوره", 15f, true))
        for ((name, amount) in equity) body.addView(row(name, if (amount < 0) -amount else null, if (amount > 0) amount else null))
        body.addView(row("جمع حقوق مالکانه با سود/زیان دوره", null, report.optLong("equity"), total = true))
        val diff = report.optLong("balanceDifference")
        body.addView(row("کنترل معادله حسابداری", if (diff > 0) diff else null, if (diff < 0) -diff else null, total = true))
        body.addView(txt("وضعیت دفتر: ${if (report.optBoolean("journalBalanced")) "جمع بدهکار و بستانکار برابر است" else "هشدار: دفتر روزنامه نامتوازن است"}. اختلاف معادله دارایی = بدهی + حقوق مالکانه: ${money(kotlin.math.abs(diff))}.", 13f, true))
        body.addView(txt("توجه: این گزارش مانده‌های فقط ثبت‌شده در دفتر مشترک را نشان می‌دهد. تا زمان انتقال و تطبیق سوابق قدیمی، خالی‌بودن یا کمتر بودن مانده‌ها به معنی صفر بودن واقعی حساب‌ها نیست.", 12f))
    }

    private fun profitLoss(body: LinearLayout) {
        body.addView(txt("گزارش سود و زیان از دفتر روزنامه مشترک", 22f, true))
        body.addView(txt("مبالغ از ثبت‌های قطعی دفتر مشترک محاسبه شده‌اند؛ اطلاعات فاکتورهایی که هنوز در دفتر ثبت نشده‌اند در این گزارش نیستند.", 13f))
        val report = SharedLedger(prefs).financialStatement()
        val rows = report.optJSONArray("accounts") ?: JSONArray()
        var totalIncome = 0L; var totalExpenses = 0L
        val incomeRows = mutableListOf<Pair<String, Long>>()
        val expenseRows = mutableListOf<Pair<String, Long>>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val name = r.optString("accountName", "حساب")
            val debit = r.optLong("debit"); val credit = r.optLong("credit")
            when (r.optString("accountClass")) {
                "INCOME" -> { val amount = credit - debit; if (amount != 0L) incomeRows += name to amount; totalIncome = Math.addExact(totalIncome, amount) }
                "EXPENSE" -> { val amount = debit - credit; if (amount != 0L) expenseRows += name to amount; totalExpenses = Math.addExact(totalExpenses, amount) }
            }
        }
        body.addView(txt("درآمدها", 15f, true))
        for ((name, amount) in incomeRows) card(body, name, money(amount)) { showReportDetails(name, "درآمد") }
        body.addView(txt("جمع درآمدها: ${money(totalIncome)}", 14f, true))
        body.addView(txt("هزینه‌ها و بهای تمام‌شده", 15f, true))
        for ((name, amount) in expenseRows) card(body, name, money(amount)) { showReportDetails(name, "هزینه") }
        body.addView(txt("جمع هزینه‌ها: ${money(totalExpenses)}", 14f, true))
        val profit = report.optLong("profit")
        body.addView(cardReportResult("سود/زیان خالص ثبت‌شده", money(profit), profit >= 0L))
        body.addView(txt("جمع بدهکار و بستانکار دفتر: ${money(report.optLong("totalDebit"))} / ${money(report.optLong("totalCredit"))}. وضعیت: ${if (report.optBoolean("journalBalanced")) "متوازن" else "نامتوازن"}.", 12f))
        body.addView(txt("گزارش تنها ثبت‌های موجود در دفتر مشترک را شامل می‌شود. طبقه‌بندی حساب‌ها و انتقال کامل سوابق قدیمی باید پیش از استفاده به‌عنوان گزارش نهایی مالی بررسی شود.", 12f))
    }

    private fun cardReportResult(title: String, value: String, positive: Boolean): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(0xFF292B33.toInt())
            addView(txt(title, 14f, true))
            addView(txt(value, 20f, true).apply { setTextColor(if (positive) 0xFF20A96B.toInt() else 0xFFE84D68.toInt()) })
            val lp = LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(5), 0, dp(5)); layoutParams = lp
        }
    }

    private fun showReportDetails(title: String, type: String) {
        val i = Intent(this, MainActivity::class.java).apply {
            putExtra("report_title", title); putExtra("report_type", type)
        }
        // Use in-app navigation so details remain inside the same Activity.
        detailTitle = title; detailType = type; showPage("جزئیات گزارش")
    }
    private var detailTitle = "گزارش"
    private var detailType = ""

    private fun reportDetails(body: LinearLayout, unusedTitle: String, unusedType: String) {
        val title = detailTitle
        val type = detailType
        body.addView(txt(title, 21f, true))
        body.addView(txt("معین ← تفصیلی ← ریزتراکنش‌ها", 13f))
        val rs = records
        var count = 0
        for (i in rs.length() - 1 downTo 0) {
            val r = rs.optJSONObject(i) ?: continue
            val t = r.optString("type")
            val match = when (type) {
                "all" -> t in listOf("فروش", "خرید", "برگشت از فروش", "برگشت از خرید", "درآمد", "هزینه")
                "asset" -> false
                "liability" -> false
                else -> t == type
            }
            if (!match) continue
            count++
            val amount = r.optLong("total", r.optLong("amount"))
            card(body, "${r.optString("date")} — $t", "${r.optString("invoiceNo")} | ${r.optString("party")} | ${r.optString("title")} | ${money(amount)}") {
                AlertDialog.Builder(this).setTitle("ریزتراکنش / تفصیلی")
                    .setMessage("تاریخ: ${r.optString("date")}\nنوع: $t\nطرف حساب: ${r.optString("party")}\nشرح: ${r.optString("title")}\nمبلغ: ${money(amount)}\n\nاطلاعات ثبت‌شده:\n${r.toString(2)}")
                    .setPositiveButton("بستن", null).show()
            }
        }
        if (count == 0) body.addView(txt("برای این ردیف هنوز ریزتراکنش قابل‌اتصال ثبت نشده است. حساب‌های صندوق/بانک و بدهی‌ها در این نسخه فقط مانده اولیه یا رکورد جداگانه دارند؛ برای دفتر معین رسمی باید ثبت‌های مالی به دفتر روزنامه مشترک متصل شوند.", 13f))
        body.addView(btn("بازگشت به گزارش‌ها") { showPage("گزارش‌ها") })
    }


    // Portfolio MVP: assets and operations are persisted locally and separated from cash accounts.
    private fun portfolioPage(body: LinearLayout) {
        body.addView(txt("پرتفوی مجازی", 22f, true).apply { setTextColor(0xFFB56BE8.toInt()) })
        body.addView(txt("دارایی‌های پرتفوی در این نسخه به‌صورت جداگانه ثبت می‌شوند؛ اتصال خودکار به دفتر روزنامه و قیمت زنده بازار هنوز نیازمند تکمیل و آزمون است.", 12f))
        body.addView(btn("＋ افزودن دارایی / ثبت خرید") { portfolioOperationDialog("خرید") })
        body.addView(btn("－ ثبت فروش دارایی") { portfolioOperationDialog("فروش") })
        val list = portfolio
        var totalValue = 0L
        var totalCost = 0L
        if (list.length() == 0) body.addView(txt("هنوز دارایی‌ای در پرتفوی ثبت نشده است."))
        for (i in 0 until list.length()) {
            val a = list.optJSONObject(i) ?: continue
            val qty = a.optDouble("quantity", 0.0)
            val price = a.optLong("lastPrice", a.optLong("buyPrice"))
            val cost = (qty * a.optLong("buyPrice")).toLong()
            val value = (qty * price).toLong()
            totalCost += cost; totalValue += value
            card(body, "${a.optString("symbol")} — ${a.optString("name")}", "تعداد: ${qty} | آخرین قیمت ثبت‌شده: ${money(price)} | ارزش: ${money(value)} | سود/زیان برآوردی: ${money(value-cost)}") {
                AlertDialog.Builder(this).setTitle(a.optString("symbol")).setItems(arrayOf("به‌روزرسانی قیمت دستی", "تنظیم هشدار قیمت/درصد", "ثبت فروش", "حذف دارایی", "لغو")) { _, choice ->
                    when (choice) {
                        0 -> {
                            val e = edit("قیمت جدید ($currency)", true).also { it.setText(a.optLong("lastPrice", a.optLong("buyPrice")).toString()) }
                            AlertDialog.Builder(this).setTitle("قیمت دارایی").setView(e).setPositiveButton("ذخیره") { _, _ -> val newPrice = e.text.toString().toLongOrNull() ?: a.optLong("lastPrice"); a.put("lastPrice", newPrice); checkPortfolioAlert(a, newPrice); save("portfolio_assets", list); showPage("پرتفوی مجازی") }.setNegativeButton("لغو", null).show()
                        }
                        1 -> portfolioAlertDialog(a, list)
                        2 -> portfolioOperationDialog("فروش", a.optString("symbol"))
                        3 -> AlertDialog.Builder(this).setMessage("این دارایی از فهرست پرتفوی حذف شود؟").setPositiveButton("حذف") { _, _ -> list.remove(i); save("portfolio_assets", list); showPage("پرتفوی مجازی") }.setNegativeButton("لغو", null).show()
                    }
                }.show()
            }
        }
        card(body, "ارزش کل پرتفوی", money(totalValue))
        card(body, "بهای خرید ثبت‌شده", money(totalCost))
        card(body, "سود/زیان تحقق‌نیافته برآوردی", money(totalValue-totalCost))
        body.addView(txt("قیمت‌ها خودکار و هر دقیقه از بورس/TSETMC دریافت نمی‌شوند؛ فعلاً قیمت باید دستی ثبت شود. قیمت زنده و هشدار قیمت/درصد پس از اتصال به منبع مجاز و آزمون شبکه اضافه می‌شود.", 12f))
        body.addView(txt("عملیات اخیر", 18f, true))
        val ops = portfolioOps
        for (i in ops.length()-1 downTo maxOf(0, ops.length()-10)) {
            val op = ops.optJSONObject(i) ?: continue
            card(body, "${op.optString("date")} — ${op.optString("type")} ${op.optString("symbol")}", "تعداد: ${op.optString("quantity")} | قیمت: ${money(op.optLong("price"))} | مبلغ: ${money(op.optLong("amount"))}")
        }
    }

    private fun portfolioAlertDialog(asset: JSONObject, assets: JSONArray) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), 0) }
        val price = edit("قیمت آستانه (اختیاری)", true).also { if (asset.optLong("alertPrice") > 0) it.setText(asset.optLong("alertPrice").toString()) }
        val percent = edit("درصد تغییر نسبت به قیمت خرید (اختیاری)", true).also { if (asset.optDouble("alertPercent") > 0) it.setText(asset.optDouble("alertPercent").toString()) }
        val priceDirection = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("وقتی قیمت به بالا رسید", "وقتی قیمت به پایین رسید")); setSelection(if (asset.optString("alertPriceDirection") == "below") 1 else 0) }
        val percentDirection = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("افزایش درصدی", "کاهش درصدی")); setSelection(if (asset.optString("alertPercentDirection") == "down") 1 else 0) }
        val enabled = CheckBox(this).apply { text = "هشدار این دارایی فعال باشد"; isChecked = asset.optBoolean("alertEnabled", false) }
        listOf(enabled, price, priceDirection, percent, percentDirection).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("هشدار مستقل ${asset.optString("symbol")}").setView(box).setPositiveButton("ذخیره") { _, _ ->
            asset.put("alertEnabled", enabled.isChecked)
                .put("alertPrice", price.text.toString().toLongOrNull() ?: 0L)
                .put("alertPriceDirection", if (priceDirection.selectedItemPosition == 0) "above" else "below")
                .put("alertPercent", percent.text.toString().toDoubleOrNull() ?: 0.0)
                .put("alertPercentDirection", if (percentDirection.selectedItemPosition == 0) "up" else "down")
            save("portfolio_assets", assets); toast("هشدار ذخیره شد"); showPage("پرتفوی مجازی")
        }.setNegativeButton("لغو", null).show()
    }

    private fun checkPortfolioAlert(asset: JSONObject, currentPrice: Long) {
        if (!asset.optBoolean("alertEnabled", false) || !prefs.getBoolean("notifications_enabled", true)) return
        val symbol = asset.optString("symbol")
        val target = asset.optLong("alertPrice", 0L)
        val direction = asset.optString("alertPriceDirection", "above")
        val priceHit = target > 0 && if (direction == "below") currentPrice <= target else currentPrice >= target
        val buy = asset.optLong("buyPrice", 0L)
        val pct = asset.optDouble("alertPercent", 0.0)
        val changePct = if (buy > 0) (currentPrice - buy).toDouble() * 100.0 / buy else 0.0
        val pctHit = pct > 0 && if (asset.optString("alertPercentDirection", "up") == "down") changePct <= -pct else changePct >= pct
        if (!priceHit && !pctHit) { asset.put("alertWasTriggered", false); return }
        if (asset.optBoolean("alertWasTriggered", false)) return
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channel = "behzad_portfolio_alerts"
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel(channel, "هشدار پرتفوی", android.app.NotificationManager.IMPORTANCE_HIGH))
            val title = "هشدار پرتفوی: $symbol"
            val message = if (priceHit) "قیمت فعلی ${money(currentPrice)}؛ آستانه ${money(target)}" else "تغییر نسبت به قیمت خرید: ${"%.2f".format(Locale.US, changePct)}٪"
            val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, channel) else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(this)
            }
            val notification = builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(message).setAutoCancel(true).build()
            nm.notify((symbol + "portfolio").hashCode(), notification)
            asset.put("alertWasTriggered", true)
        } catch (_: Exception) { }
    }

    private fun portfolioOperationDialog(type: String, presetSymbol: String = "") {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(5), dp(20), 0) }
        val symbol = edit("نماد / نام دارایی").also { it.setText(presetSymbol) }
        val name = edit("نام دارایی (مثلاً طلا، سهام، ارز دیجیتال)")
        val qty = edit("تعداد / مقدار", true)
        val price = edit("قیمت هر واحد ($currency)", true)
        listOf(symbol, name, qty, price).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("ثبت $type پرتفوی").setView(box).setPositiveButton("ثبت") { _, _ ->
            val sym = symbol.text.toString().trim()
            val q = qty.text.toString().toDoubleOrNull() ?: 0.0
            val pr = price.text.toString().toLongOrNull() ?: 0L
            if (sym.isBlank() || q <= 0 || pr <= 0) { toast("نماد، تعداد و قیمت معتبر وارد کنید"); return@setPositiveButton }
            val assets = portfolio
            var idx = -1
            for (i in 0 until assets.length()) if (assets.optJSONObject(i)?.optString("symbol") == sym) { idx = i; break }
            val amount = (q * pr).toLong()
            if (amount <= 0L) { toast("مبلغ عملیات خارج از محدوده مجاز است"); return@setPositiveButton }
            val existingAsset = if (idx >= 0) assets.optJSONObject(idx) else null
            if (type != "خرید" && (existingAsset == null || existingAsset.optDouble("quantity", 0.0) < q)) {
                toast("دارایی کافی برای فروش ثبت نشده است"); return@setPositiveButton
            }
            val estimatedCost = if (existingAsset != null) (q * existingAsset.optLong("buyPrice", pr)).toLong() else amount
            val opId = "PORT-${System.currentTimeMillis()}-${sym.hashCode()}"
            try { SharedLedger(prefs).postPortfolio(type, opId, now(), sym, amount, q, pr, estimatedCost) }
            catch (e: Exception) { toast("ثبت پرتفوی در دفتر روزنامه انجام نشد: ${e.message ?: "خطای حسابداری"}"); return@setPositiveButton }
            if (type == "خرید") {
                val a = if (idx >= 0) assets.optJSONObject(idx)!! else JSONObject().put("symbol", sym).put("name", name.text.toString().ifBlank { sym }).put("quantity", 0.0).put("buyPrice", 0L).put("lastPrice", pr)
                val oldQty = a.optDouble("quantity", 0.0); val oldCost = oldQty * a.optLong("buyPrice", 0L)
                a.put("quantity", oldQty + q).put("buyPrice", if (oldQty + q > 0) ((oldCost + amount) / (oldQty + q)).toLong() else pr).put("lastPrice", pr)
                if (idx >= 0) assets.put(idx, a) else assets.put(a)
                save("portfolio_assets", assets)
            } else {
                if (idx < 0 || assets.optJSONObject(idx)!!.optDouble("quantity", 0.0) < q) { toast("دارایی کافی برای فروش ثبت نشده است"); return@setPositiveButton }
                val a = assets.optJSONObject(idx)!!; a.put("quantity", a.optDouble("quantity") - q).put("lastPrice", pr)
                if (a.optDouble("quantity") <= 0.0000001) assets.remove(idx) else assets.put(idx, a)
                save("portfolio_assets", assets)
            }
            val ops = portfolioOps
            ops.put(JSONObject().put("id", opId).put("type", type).put("symbol", sym).put("quantity", q).put("price", pr).put("amount", amount).put("date", now()))
            save("portfolio_operations", ops); showPage("پرتفوی مجازی")
        }.setNegativeButton("لغو", null).show()
    }

    private fun alarmsPage(body: LinearLayout) {
        body.addView(txt("آلارم‌ها و یادآورها", 22f, true).apply { setTextColor(0xFFB56BE8.toInt()) })
        body.addView(txt("یادآوری‌ها هر روز ساعت ۰۹:۰۰ بررسی می‌شوند؛ سررسیدها ۳ روز پیش از تاریخ ثبت‌شده یادآوری می‌شوند. تاریخ سررسید را دقیقاً به شکل میلادی YYYY-MM-DD وارد کنید. در Android 13 به بالا اجازه اعلان باید فعال باشد.", 12f))
        fun toggle(key: String, label: String, default: Boolean = true) {
            val cb = CheckBox(this).apply { text = label; isChecked = prefs.getBoolean(key, default); setTextColor(0xFFFFFFFF.toInt()); buttonTintList = android.content.res.ColorStateList.valueOf(0xFFB56BE8.toInt()) }
            cb.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
            body.addView(cb)
        }
        toggle("notifications_enabled", "فعال بودن همه اعلان‌ها")
        toggle("transaction_reminders", "یادآور تراکنش‌ها")
        toggle("check_reminders", "یادآور سررسید چک‌ها")
        toggle("installment_reminders", "یادآور اقساط")
        toggle("receivable_payable_reminders", "یادآور مطالبات و بدهی‌ها")
        body.addView(txt("زمان پیش‌فرض یادآوری: ۳ روز قبل از سررسید، ساعت ۰۹:۰۰", 14f, true).apply { setTextColor(-1) })
        body.addView(btn("ذخیره تنظیمات و زمان‌بندی یادآورها") { scheduleDailyReminder(); toast(if (prefs.getBoolean("notifications_enabled", true)) "تنظیمات ذخیره شد؛ بررسی روزانه ساعت ۰۹:۰۰ زمان‌بندی شد" else "تنظیمات ذخیره شد؛ اعلان‌ها خاموش هستند") })
    }

    private fun settings(body: LinearLayout) {
        body.addView(txt("تنظیمات",21f,true))
        body.addView(btn("تغییر واحد پول: $currency") { showCurrencyDialog(false) })
        body.addView(btn("پرتفوی مجازی") { showPage("پرتفوی مجازی") })
        body.addView(btn("تنظیم یادآورها و آلارم‌ها") { showPage("آلارم‌ها") })
        body.addView(btn("خروجی Excel (.xlsx)") { exportExcel() })
        body.addView(btn("خروجی گزارش PDF (.pdf)") { exportPdf() })
        body.addView(btn("ذخیره فایل پشتیبان") { exportBackup() })
        body.addView(btn("بازیابی از فایل پشتیبان") { restoreBackup() })
        body.addView(btn("راهنمای امکانات") {
            AlertDialog.Builder(this).setTitle("راهنما").setMessage("کالا و انبار: تعریف کالا و ورود/خروج موجودی.\nفروش/خرید: ثبت فاکتور تک‌قلمی و چاپ.\nاشخاص: مشتری و تأمین‌کننده.\nصندوق و بانک: ثبت حساب و مانده اولیه.\nبدهی‌ها: ثبت و تسویه دستی بدهی/طلب.\nگزارش‌ها: خلاصه فروش، خرید، درآمد و هزینه.")
                .setPositiveButton("متوجه شدم",null).show()
        })
        body.addView(txt("نسخه ۴٫۳٫۵ — تنظیمات روی همین دستگاه ذخیره می‌شود. پیش از بازیابی، از اطلاعات نسخهٔ پشتیبان تهیه کنید.",13f))
    }


    // Export a real Office Open XML .xlsx workbook without requiring an extra library.
    private fun exportExcel() {
        pendingExportType = "xlsx"
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_TITLE,"hesabdari-behzad-${System.currentTimeMillis()}.xlsx")
        }
        startActivityForResult(intent,REQUEST_EXPORT_XLSX)
    }

    private fun xmlEscape(value: String): String = value
        .replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        .replace("\"","&quot;").replace("'","&apos;")

    private fun xlsxCell(value: String, ref: String): String =
        "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEscape(value)}</t></is></c>"

    private fun colName(n: Int): String {
        var x=n+1; var out=""
        while(x>0) { val r=(x-1)%26; out=('A'.code+r).toChar()+out; x=(x-1)/26 }
        return out
    }

    private fun sheetXml(rows: List<List<String>>): String {
        val body=rows.mapIndexed { ri,row ->
            val cells=row.mapIndexed { ci,v -> xlsxCell(v, colName(ci)+(ri+1)) }.joinToString("")
            "<row r=\"${ri+1}\">$cells</row>"
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$body</sheetData></worksheet>""".trimIndent()
    }

    private fun buildXlsx(): ByteArray {
        val sheetRows= mutableListOf<List<List<String>>>()
        val rs=records
        val tx= mutableListOf(listOf("نوع","تاریخ","طرف حساب","شرح","مبلغ","واحد پول","تعداد اقلام"))
        for(i in 0 until rs.length()) {
            val r=rs.optJSONObject(i) ?: continue
            tx.add(listOf(r.optString("type"),r.optString("date"),r.optString("party"),r.optString("title"),
                r.optLong("total",r.optLong("amount")).toString(),r.optString("currency",currency),
                (r.optJSONArray("items")?.length() ?: 0).toString()))
        }
        val ps=products
        val pr= mutableListOf(listOf("نام کالا","کد/بارکد","قیمت خرید","قیمت فروش","موجودی"))
        for(i in 0 until ps.length()) {
            val p=ps.optJSONObject(i) ?: continue
            pr.add(listOf(p.optString("name"),p.optString("code"),p.optLong("buy").toString(),
                p.optLong("sell").toString(),p.optDouble("stock").toLong().toString()))
        }
        val items= mutableListOf(listOf("شماره فاکتور","نوع","تاریخ","طرف حساب","کالا","کد کالا","تعداد","قیمت واحد","جمع قلم"))
        for(i in 0 until rs.length()) {
            val r=rs.optJSONObject(i) ?: continue
            val its=r.optJSONArray("items") ?: continue
            for(j in 0 until its.length()) {
                val it=its.optJSONObject(j) ?: continue
                items.add(listOf((i+1).toString(),r.optString("type"),r.optString("date"),r.optString("party"),
                    it.optString("product"),it.optString("code"),it.optLong("qty").toString(),
                    it.optLong("unitPrice").toString(),it.optLong("lineTotal").toString()))
            }
        }
        val peopleRows= mutableListOf(listOf("نام","نوع","تلفن"))
        val pe=people
        for(i in 0 until pe.length()) { val p=pe.optJSONObject(i) ?: continue
            peopleRows.add(listOf(p.optString("name"),p.optString("kind"),p.optString("phone"))) }
        val debtRows= mutableListOf(listOf("طرف حساب","شرح","نوع","مبلغ","وضعیت","تاریخ"))
        val ds=arr("debts")
        for(i in 0 until ds.length()) { val d=ds.optJSONObject(i) ?: continue
            debtRows.add(listOf(d.optString("person"),d.optString("title"),d.optString("kind"),d.optLong("amount").toString(),d.optString("status"),d.optString("date"))) }
        val accountRows= mutableListOf(listOf("نام حساب","نوع","مانده اولیه"))
        val ac=accounts
        for(i in 0 until ac.length()) { val a=ac.optJSONObject(i) ?: continue
            accountRows.add(listOf(a.optString("name"),a.optString("kind"),a.optLong("balance").toString())) }
        sheetRows.add(tx); sheetRows.add(pr); sheetRows.add(items); sheetRows.add(peopleRows); sheetRows.add(debtRows); sheetRows.add(accountRows)
        val names=listOf("تراکنش‌ها","کالاها","ریز فاکتورها","اشخاص","بدهی‌ها","حساب‌ها")
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(path:String, content:String) {
                zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                ${sheetRows.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it+1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }}
                </Types>""".trimIndent())
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>""".trimIndent())
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets>${names.mapIndexed { i,n -> "<sheet name=\"${xmlEscape(n)}\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>" }.joinToString("")}</sheets></workbook>""".trimIndent())
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                ${names.indices.joinToString("") { "<Relationship Id=\"rId${it+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it+1}.xml\"/>" }}
                </Relationships>""".trimIndent())
            sheetRows.forEachIndexed { i,rows -> put("xl/worksheets/sheet${i+1}.xml",sheetXml(rows)) }
        }
        return out.toByteArray()
    }

    private fun exportPdf() {
        pendingExportType = "pdf"
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type="application/pdf"
            putExtra(Intent.EXTRA_TITLE,"hesabdari-behzad-report-${System.currentTimeMillis()}.pdf")
        }
        startActivityForResult(intent,REQUEST_EXPORT_PDF)
    }

    private fun buildPdf(): ByteArray {
        val document=PdfDocument()
        val pageWidth=595; val pageHeight=842; val margin=36
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize=11f; color=android.graphics.Color.BLACK }
        val titlePaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize=18f; isFakeBoldText=true; color=android.graphics.Color.BLACK }
        val lines= mutableListOf<String>()
        lines.add("گزارش حسابداری بهزاد")
        lines.add("تاریخ تهیه: ${now()}    واحد پول: $currency")
        lines.add(" ")
        val rs=records
        var sales=0L; var salesReturns=0L; var purchases=0L; var purchaseReturns=0L; var incomes=0L; var expenses=0L; var cogs=0L; var returnedCogs=0L
        lines.add("خلاصه تراکنش‌ها")
        for(i in 0 until rs.length()) {
            val r=rs.optJSONObject(i) ?: continue
            val amount=r.optLong("total",r.optLong("amount"))
            when(r.optString("type")) {
                "فروش" -> { sales+=amount; val its=r.optJSONArray("items") ?: JSONArray(); for(j in 0 until its.length()) { val it=its.optJSONObject(j) ?: continue; cogs+=it.optLong("qty")*it.optLong("unitCost") } }
                "برگشت از فروش" -> { salesReturns+=amount; val its=r.optJSONArray("items") ?: JSONArray(); for(j in 0 until its.length()) { val it=its.optJSONObject(j) ?: continue; returnedCogs+=it.optLong("qty")*it.optLong("unitCost") } }
                "خرید" -> purchases+=amount
                "برگشت از خرید" -> purchaseReturns+=amount
                "درآمد" -> incomes+=amount
                "هزینه" -> expenses+=amount
            }
            lines.add("${r.optString("date")} | ${r.optString("type")} | فاکتور ${r.optString("invoiceNo")} | مرجع ${r.optString("referenceInvoice")} | ${r.optString("party")} | ${r.optString("title")} | ${money(amount)}")
            val its=r.optJSONArray("items") ?: JSONArray()
            for(j in 0 until its.length()) { val it=its.optJSONObject(j) ?: continue
                lines.add("   ${it.optString("product")} - ${it.optLong("qty")} × ${money(it.optLong("unitPrice"))} = ${money(it.optLong("lineTotal"))}") }
        }
        lines.add(" "); lines.add("فروش ناخالص: ${money(sales)}")
        lines.add("برگشت از فروش: ${money(salesReturns)}"); lines.add("فروش خالص: ${money(sales-salesReturns)}")
        lines.add("خرید ناخالص: ${money(purchases)}"); lines.add("برگشت از خرید: ${money(purchaseReturns)}"); lines.add("خرید خالص: ${money(purchases-purchaseReturns)}")
        lines.add("درآمد: ${money(incomes)}"); lines.add("هزینه: ${money(expenses)}")
        lines.add("بهای تمام‌شده برآوردی پس از مرجوعی: ${money((cogs-returnedCogs).coerceAtLeast(0L))}")
        lines.add("سود ناخالص برآوردی: ${money(sales-salesReturns-(cogs-returnedCogs).coerceAtLeast(0L))}")
        lines.add("سود خالص برآوردی فروشگاه: ${money(sales-salesReturns-(cogs-returnedCogs).coerceAtLeast(0L)+incomes-expenses)}")
        lines.add(" "); lines.add("فهرست کالاها")
        val ps=products
        for(i in 0 until ps.length()) { val p=ps.optJSONObject(i) ?: continue
            lines.add("${p.optString("name")} | کد: ${p.optString("code")} | موجودی: ${p.optDouble("stock").toLong()} | خرید: ${money(p.optLong("buy"))} | فروش: ${money(p.optLong("sell"))}") }
        var pageNo=1
        var pdfPage=document.startPage(PdfDocument.PageInfo.Builder(pageWidth,pageHeight,pageNo).create())
        var y=margin
        fun nextPage() { document.finishPage(pdfPage); pageNo++; pdfPage=document.startPage(PdfDocument.PageInfo.Builder(pageWidth,pageHeight,pageNo).create()); y=margin }
        val canvas=pdfPage.canvas
        // Render each line with StaticLayout so Persian shaping and right-to-left text are handled.
        for((idx,line) in lines.withIndex()) {
            val p=if(idx==0) titlePaint else paint
            val layout=StaticLayout.Builder.obtain(line,0,line.length,p,pageWidth-2*margin)
                .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
                .setTextDirection(android.text.TextDirectionHeuristics.RTL)
                .setIncludePad(false).build()
            val h=layout.height.coerceAtLeast(16)
            if(y+h>pageHeight-margin) { nextPage() }
            pdfPage.canvas.save()
            pdfPage.canvas.translate(margin.toFloat(),y.toFloat())
            layout.draw(pdfPage.canvas)
            pdfPage.canvas.restore()
            y+=h+6
        }
        document.finishPage(pdfPage)
        val output=ByteArrayOutputStream()
        document.writeTo(output); document.close()
        return output.toByteArray()
    }

    private fun shareExport(uri: Uri, mime: String, title: String) {
        try {
            val share=Intent(Intent.ACTION_SEND).apply {
                type=mime; putExtra(Intent.EXTRA_STREAM,uri); putExtra(Intent.EXTRA_SUBJECT,title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share,"اشتراک‌گذاری فایل"))
        } catch (_: Exception) { toast("فایل ذخیره شد؛ اشتراک‌گذاری در این دستگاه در دسترس نیست") }
    }

    private fun exportBackup() {
        val backup=JSONObject().put("app","حسابداری بهزاد").put("version",4).put("currency",currency)
            .put("records",records).put("products",products).put("people",people)
            .put("accounts",accounts).put("debts",arr("debts")).put("installments",arr("installments")).put("checks",arr("checks"))
            .put("portfolio_assets",portfolio).put("portfolio_operations",portfolioOps)
            .put("settings", JSONObject().apply {
                listOf("notifications_enabled", "transaction_reminders", "check_reminders",
                    "installment_reminders", "receivable_payable_reminders").forEach { key ->
                    put(key, prefs.getBoolean(key, true))
                }
            }).toString(2)
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type="application/json"
            putExtra(Intent.EXTRA_TITLE,"hesabdari-behzad-backup.json")
        }
        backupPending=backup
        startActivityForResult(intent,REQUEST_BACKUP)
    }
    private var backupPending: String? = null

    private fun restoreBackup() {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type="application/json"
        }
        startActivityForResult(intent,REQUEST_RESTORE)
    }

    @Deprecated("Use Activity Result APIs in a future update")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK || data?.data==null) return
        val uri=data.data!!
        try {
            if(requestCode==REQUEST_EXPORT_XLSX || requestCode==REQUEST_EXPORT_PDF) {
                val mime=if(requestCode==REQUEST_EXPORT_XLSX) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "application/pdf"
                val bytes=if(requestCode==REQUEST_EXPORT_XLSX) buildXlsx() else buildPdf()
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Output stream unavailable")
                pendingExportType=null
                toast("فایل ذخیره شد")
                AlertDialog.Builder(this).setTitle("خروجی آماده است")
                    .setMessage("فایل با موفقیت ذخیره شد. می‌توانید آن را به اشتراک بگذارید.")
                    .setPositiveButton("اشتراک‌گذاری") { _,_ -> shareExport(uri,mime,if(requestCode==REQUEST_EXPORT_XLSX) "گزارش Excel حسابداری بهزاد" else "گزارش PDF حسابداری بهزاد") }
                    .setNegativeButton("بستن",null).show()
            } else if(requestCode==REQUEST_BACKUP) {
                val content=backupPending ?: return
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                backupPending=null
                toast("نسخه پشتیبان ذخیره شد")
            } else if(requestCode==REQUEST_RESTORE) {
                val content=contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val obj=JSONObject(content)
                val required=arrayOf("records","products","people","accounts","debts")
                if(required.any { !obj.has(it) || obj.optJSONArray(it)==null }) {
                    toast("فایل پشتیبان معتبر نیست"); return
                }
                AlertDialog.Builder(this).setTitle("بازیابی نسخه پشتیبان")
                    .setMessage("اطلاعات فعلی با اطلاعات فایل جایگزین می‌شود. ادامه می‌دهید؟")
                    .setPositiveButton("بازیابی") { _,_ ->
                        prefs.edit()
                            .putString("currency",obj.optString("currency","تومان"))
                            .putString("records",obj.getJSONArray("records").toString())
                            .putString("products",obj.getJSONArray("products").toString())
                            .putString("people",obj.getJSONArray("people").toString())
                            .putString("accounts",obj.getJSONArray("accounts").toString())
                            .putString("debts",obj.getJSONArray("debts").toString())
                            .putString("installments",(obj.optJSONArray("installments") ?: JSONArray()).toString())
                            .putString("checks",(obj.optJSONArray("checks") ?: JSONArray()).toString())
                            .putString("portfolio_assets", (obj.optJSONArray("portfolio_assets") ?: JSONArray()).toString())
                            .putString("portfolio_operations", (obj.optJSONArray("portfolio_operations") ?: JSONArray()).toString())
                            .putBoolean("configured",true).apply()
                        val restoredSettings = obj.optJSONObject("settings")
                        if (restoredSettings != null) {
                            val editor = prefs.edit()
                            listOf("notifications_enabled", "transaction_reminders", "check_reminders",
                                "installment_reminders", "receivable_payable_reminders").forEach { key ->
                                if (restoredSettings.has(key)) editor.putBoolean(key, restoredSettings.optBoolean(key, true))
                            }
                            editor.apply()
                        }
                        toast("بازیابی انجام شد"); showPage("خانه")
                    }.setNegativeButton("لغو",null).show()
            }
        } catch (_: Exception) { toast("خواندن یا نوشتن فایل انجام نشد") }
    }

    private fun showCurrencyDialog(firstRun:Boolean) {
        val options=arrayOf("تومان","ریال")
        AlertDialog.Builder(this).setTitle("انتخاب واحد پول")
            .setSingleChoiceItems(options,if(currency=="ریال")1 else 0) { dialog,which ->
                prefs.edit().putString("currency",options[which]).putBoolean("configured",true).apply()
                dialog.dismiss(); showPage(page)
            }.setCancelable(!firstRun).show()
    }
    private fun money(v:Long)=DecimalFormat("#,###").format(v)+" "+currency
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
