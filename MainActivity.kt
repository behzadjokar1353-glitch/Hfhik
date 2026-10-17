package ir.behzad.accounting

import android.app.Activity
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
    private fun now() = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("fa")).format(Date())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
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
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(5), dp(12), dp(5))
            setBackgroundColor(0xFFFFFFFF.toInt())
            if (action != null) setOnClickListener { action() }
        }
        box.addView(txt(title, 14f, true))
        if (value != null) box.addView(txt(value, 18f, true))
        val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(8)
        body.addView(box, lp)
    }

    private fun showPage(name: String) {
        page = name
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFF4F7F6.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply {
            text = "حسابداری بهزاد"; textSize = 22f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(-1); gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(13), dp(18), dp(13)); setBackgroundColor(0xFF087F70.toInt())
        })
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(16)) }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        when (name) {
            "خانه" -> home(body)
            "فروش" -> invoiceForm(body, "فروش")
            "خرید" -> invoiceForm(body, "خرید")
            "هزینه" -> simpleTransaction(body, "هزینه")
            "درآمد" -> simpleTransaction(body, "درآمد")
            "کالا و انبار" -> inventory(body)
            "اشخاص" -> peoplePage(body)
            "صندوق و بانک" -> accountsPage(body)
            "بدهی‌ها" -> debtsPage(body)
            "سوابق" -> history(body)
            "گزارش‌ها" -> reports(body)
            "تنظیمات" -> settings(body)
            else -> home(body)
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setBackgroundColor(-1) }
        listOf("خانه", "فروش", "کالا و انبار", "اشخاص", "بیشتر").forEach { item ->
            nav.addView(TextView(this).apply {
                text = item; textSize = 11f; gravity = Gravity.CENTER
                setPadding(dp(3), dp(12), dp(3), dp(12))
                setTextColor(if (page == item) 0xFF087F70.toInt() else 0xFF47534F.toInt())
                setOnClickListener {
                    if (item == "بیشتر") moreMenu() else showPage(item)
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
    }

    private fun moreMenu() {
        val options = arrayOf("خرید", "درآمد", "هزینه", "صندوق و بانک", "بدهی‌ها", "گزارش‌ها", "سوابق", "تنظیمات")
        AlertDialog.Builder(this).setTitle("بخش‌های برنامه").setItems(options) { _, i -> showPage(options[i]) }.show()
    }

    private fun home(body: LinearLayout) {
        body.addView(txt("داشبورد مالی", 21f, true))
        body.addView(txt("واحد پول فعال: $currency", 14f))
        var sales=0L; var purchases=0L; var incomes=0L; var expenses=0L
        val rs=records
        for (i in 0 until rs.length()) {
            val r=rs.optJSONObject(i) ?: continue
            when(r.optString("type")) {
                "فروش" -> sales += r.optLong("total")
                "خرید" -> purchases += r.optLong("total")
                "درآمد" -> incomes += r.optLong("amount")
                "هزینه" -> expenses += r.optLong("amount")
            }
        }
        card(body, "فروش ثبت‌شده", money(sales)); card(body, "خرید ثبت‌شده", money(purchases))
        card(body, "درآمد شخصی", money(incomes)); card(body, "هزینه‌ها", money(expenses))
        card(body, "تعداد کالاها", products.length().toString())
        body.addView(txt("دسترسی سریع", 18f, true))
        listOf("فروش","خرید","کالا و انبار","اشخاص","صندوق و بانک","بدهی‌ها","گزارش‌ها").forEach { body.addView(btn(it) { showPage(it) }) }
        body.addView(txt("اطلاعات در حافظه محلی همین گوشی ذخیره می‌شود. پشتیبان‌گیری منظم توصیه می‌شود.", 12f))
    }

    private fun inventory(body: LinearLayout) {
        body.addView(txt("کالا و انبار", 21f, true))
        body.addView(btn("＋ افزودن کالا") { productDialog(-1) })
        val ps=products
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
                            2 -> AlertDialog.Builder(this).setMessage("کالا حذف شود؟")
                                .setPositiveButton("حذف") { _,_ -> val a=products; a.remove(i); save("products",a); showPage("کالا و انبار") }
                                .setNegativeButton("لغو",null).show()
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
        val stock=edit("موجودی فعلی",true).also { if(old!=null) it.setText(old.optDouble("stock").toLong().toString()) }
        listOf(name,code,buy,sell,stock).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle(if(old==null) "افزودن کالا" else "ویرایش کالا").setView(box)
            .setPositiveButton("ذخیره") { _,_ ->
                if(name.text.toString().isBlank()) { toast("نام کالا الزامی است"); return@setPositiveButton }
                val p=JSONObject().put("name",name.text.toString()).put("code",code.text.toString())
                    .put("buy",buy.text.toString().toLongOrNull()?:0).put("sell",sell.text.toString().toLongOrNull()?:0)
                    .put("stock",stock.text.toString().toLongOrNull()?:0)
                val a=products
                if(index>=0) a.put(index,p) else a.put(p)
                save("products",a); showPage("کالا و انبار")
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
                p.put("stock", if(chosen==0) current+n else current-n)
                save("products",products)
                val rs=records
                rs.put(JSONObject().put("type",if(chosen==0)"ورود انبار" else "خروج انبار").put("title",p.optString("name")).put("amount",n).put("date",now()).put("currency","عدد"))
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
        val customer=edit("نام مشتری / تأمین‌کننده")
        val desc=edit("شرح فاکتور (اختیاری)")
        body.addView(customer); body.addView(desc)
        val linesBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val lines= mutableListOf<InvoiceLine>()
        body.addView(linesBox)
        fun addLine(preselectCode: String? = null) {
            val ps=products
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
                price.setText((if(type=="فروش") prod.optLong("sell") else prod.optLong("buy")).toString())
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
            val productsNow=products
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
                val stock=product.optLong("stock")
                if(type=="فروش" && stock<count) { toast("موجودی ${product.optString("name")} کافی نیست"); return@btn }
                total += count*unit
                builtLines.put(JSONObject()
                    .put("product",product.optString("name"))
                    .put("code",product.optString("code"))
                    .put("qty",count).put("unitPrice",unit)
                    .put("unitCost",product.optLong("buy"))
                    .put("lineTotal",count*unit))
                changes.add(product to count)
            }
            if(total<=0) { toast("مبلغ فاکتور باید بیشتر از صفر باشد"); return@btn }
            // Apply stock movements only after all lines have passed validation.
            changes.forEach { (product,count) ->
                val old=product.optLong("stock")
                product.put("stock",if(type=="فروش") old-count else old+count)
            }
            save("products",productsNow)
            val r=JSONObject().put("type",type).put("party",customer.text.toString())
                .put("title",desc.text.toString().ifBlank { "فاکتور $type" })
                .put("amount",total).put("total",total).put("items",builtLines)
                .put("date",now()).put("currency",currency)
            records.put(r); save("records",records)
            if(customer.text.toString().isNotBlank()) savePersonIfMissing(customer.text.toString(),if(type=="فروش")"مشتری" else "تأمین‌کننده")
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
            val a=records
            a.put(JSONObject().put("type",type).put("title",title.text.toString().ifBlank{type})
                .put("amount",n).put("date",now()).put("currency",currency))
            save("records",a); toast("$type ثبت شد"); showPage("خانه")
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
            card(body,x.optString("name")+" — "+x.optString("kind"),"مانده اولیه: ${money(x.optLong("balance"))}") {
                accountDialog(i)
            }
        }
    }
    private fun accountDialog(index:Int) {
        val old=if(index>=0) accounts.optJSONObject(index) else null
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),0,dp(18),0) }
        val name=edit("نام صندوق یا بانک").also { if(old!=null) it.setText(old.optString("name")) }
        val balance=edit("مانده اولیه ($currency)",true).also { if(old!=null) it.setText(old.optLong("balance").toString()) }
        val sp=Spinner(this); sp.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("صندوق نقدی","حساب بانکی","کیف پول"))
        if(old!=null) sp.setSelection(arrayOf("صندوق نقدی","حساب بانکی","کیف پول").indexOf(old.optString("kind")).coerceAtLeast(0))
        box.addView(name); box.addView(balance); box.addView(sp)
        AlertDialog.Builder(this).setTitle("حساب مالی").setView(box).setPositiveButton("ذخیره") { _,_ ->
            if(name.text.isBlank()) { toast("نام حساب الزامی است"); return@setPositiveButton }
            val x=JSONObject().put("name",name.text.toString()).put("balance",balance.text.toString().toLongOrNull()?:0).put("kind",sp.selectedItem.toString())
            val a=accounts; if(index>=0) a.put(index,x) else a.put(x); save("accounts",a); showPage("صندوق و بانک")
        }.setNegativeButton("لغو",null).show()
    }

    private fun debtsPage(body: LinearLayout) {
        body.addView(txt("بدهی‌ها و مطالبات",21f,true))
        body.addView(btn("＋ ثبت بدهی یا طلب") { debtDialog() })
        val a=arr("debts")
        if(a.length()==0) body.addView(txt("موردی ثبت نشده است."))
        for(i in 0 until a.length()) {
            val d=a.optJSONObject(i) ?: continue
            card(body,"${d.optString("kind")} — ${d.optString("person")}", "${d.optString("title")} | ${money(d.optLong("amount"))} | ${d.optString("status")}") {
                AlertDialog.Builder(this).setTitle("وضعیت مورد").setItems(arrayOf("علامت‌گذاری تسویه‌شده","حذف","لغو")) { _,w ->
                    when(w) {
                        0 -> { d.put("status","تسویه‌شده"); save("debts",a); showPage("بدهی‌ها") }
                        1 -> { a.remove(i); save("debts",a); showPage("بدهی‌ها") }
                    }
                }.show()
            }
        }
    }
    private fun debtDialog() {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),0,dp(18),0) }
        val person=edit("نام طرف حساب"); val title=edit("شرح بدهی یا طلب"); val amount=edit("مبلغ ($currency)",true)
        val sp=Spinner(this); sp.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("بدهی من","طلب من"))
        listOf(person,title,amount,sp).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("ثبت بدهی/طلب").setView(box).setPositiveButton("ذخیره") { _,_ ->
            val n=amount.text.toString().toLongOrNull()
            if(person.text.isBlank() || n==null || n<=0) { toast("نام و مبلغ معتبر وارد کنید"); return@setPositiveButton }
            val a=arr("debts"); a.put(JSONObject().put("person",person.text.toString()).put("title",title.text.toString()).put("amount",n)
                .put("kind",sp.selectedItem.toString()).put("status","باز").put("date",now()))
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
        body.addView(txt("گزارش‌های مالی",21f,true))
        val a=records
        var sales=0L; var purchases=0L; var incomes=0L; var expenses=0L; var cogs=0L
        for(i in 0 until a.length()) {
            val r=a.optJSONObject(i) ?: continue
            when(r.optString("type")) {
                "فروش" -> {
                    sales+=r.optLong("total",r.optLong("amount"))
                    val items=r.optJSONArray("items") ?: JSONArray()
                    for(j in 0 until items.length()) {
                        val item=items.optJSONObject(j) ?: continue
                        cogs += item.optLong("qty") * item.optLong("unitCost")
                    }
                }
                "خرید" -> purchases+=r.optLong("total",r.optLong("amount"))
                "درآمد" -> incomes+=r.optLong("amount")
                "هزینه" -> expenses+=r.optLong("amount")
            }
        }
        val gross=sales-cogs
        val net=gross-expenses
        card(body,"فروش کل",money(sales))
        card(body,"بهای تمام‌شده کالای فروخته‌شده (برآورد بر اساس قیمت خرید ثبت‌شده)",money(cogs))
        card(body,"سود ناخالص برآوردی",money(gross))
        card(body,"هزینه‌های ثبت‌شده",money(expenses))
        card(body,"سود خالص برآوردی فروشگاه",money(net))
        card(body,"خرید کل",money(purchases))
        card(body,"درآمد شخصی (جدا از فروش)",money(incomes))
        card(body,"تعداد فاکتورها و تراکنش‌ها",a.length().toString())
        body.addView(btn("ذخیره همین گزارش در Excel") { exportExcel() })
        body.addView(btn("ذخیره گزارش در PDF") { exportPdf() })
        body.addView(txt("مهم: سود بر اساس قیمت خرید ذخیره‌شده در کالا هنگام فروش محاسبه می‌شود. برای سود حسابداری قطعی باید تخفیف، برگشت، هزینه‌های مستقیم، مالیات و روش بهایابی انبار هم ثبت شود. فاکتورهای قدیمی فاقد ریز اقلام در محاسبه بهای تمام‌شده لحاظ نمی‌شوند.",12f))
    }

    private fun settings(body: LinearLayout) {
        body.addView(txt("تنظیمات",21f,true))
        body.addView(btn("تغییر واحد پول: $currency") { showCurrencyDialog(false) })
        body.addView(btn("خروجی Excel (.xlsx)") { exportExcel() })
        body.addView(btn("خروجی گزارش PDF (.pdf)") { exportPdf() })
        body.addView(btn("ذخیره فایل پشتیبان") { exportBackup() })
        body.addView(btn("بازیابی از فایل پشتیبان") { restoreBackup() })
        body.addView(btn("راهنمای امکانات") {
            AlertDialog.Builder(this).setTitle("راهنما").setMessage("کالا و انبار: تعریف کالا و ورود/خروج موجودی.\nفروش/خرید: ثبت فاکتور تک‌قلمی و چاپ.\nاشخاص: مشتری و تأمین‌کننده.\nصندوق و بانک: ثبت حساب و مانده اولیه.\nبدهی‌ها: ثبت و تسویه دستی بدهی/طلب.\nگزارش‌ها: خلاصه فروش، خرید، درآمد و هزینه.")
                .setPositiveButton("متوجه شدم",null).show()
        })
        body.addView(txt("نسخه ۱.۱.۰ — اطلاعات روی همین دستگاه نگهداری می‌شود.",13f))
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
        var sales=0L; var purchases=0L; var incomes=0L; var expenses=0L; var cogs=0L
        lines.add("خلاصه تراکنش‌ها")
        for(i in 0 until rs.length()) {
            val r=rs.optJSONObject(i) ?: continue
            val amount=r.optLong("total",r.optLong("amount"))
            when(r.optString("type")) {
                "فروش" -> { sales+=amount; val its=r.optJSONArray("items") ?: JSONArray()
                    for(j in 0 until its.length()) { val it=its.optJSONObject(j) ?: continue; cogs+=it.optLong("qty")*it.optLong("unitCost") } }
                "خرید" -> purchases+=amount
                "درآمد" -> incomes+=amount
                "هزینه" -> expenses+=amount
            }
            lines.add("${r.optString("date")} | ${r.optString("type")} | ${r.optString("party")} | ${r.optString("title")} | ${money(amount)}")
            val its=r.optJSONArray("items") ?: JSONArray()
            for(j in 0 until its.length()) { val it=its.optJSONObject(j) ?: continue
                lines.add("   ${it.optString("product")} - ${it.optLong("qty")} × ${money(it.optLong("unitPrice"))} = ${money(it.optLong("lineTotal"))}") }
        }
        lines.add(" "); lines.add("جمع فروش: ${money(sales)}")
        lines.add("جمع خرید: ${money(purchases)}"); lines.add("درآمد: ${money(incomes)}")
        lines.add("هزینه: ${money(expenses)}"); lines.add("بهای تمام‌شده برآوردی: ${money(cogs)}")
        lines.add("سود ناخالص برآوردی: ${money(sales-cogs)}")
        lines.add("سود خالص برآوردی فروشگاه: ${money(sales-cogs-expenses)}")
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
        val backup=JSONObject().put("app","حسابداری بهزاد").put("version",3).put("currency",currency)
            .put("records",records).put("products",products).put("people",people)
            .put("accounts",accounts).put("debts",arr("debts")).toString(2)
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
                            .putBoolean("configured",true).apply()
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
