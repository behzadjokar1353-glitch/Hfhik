package ir.behzad.accounting

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persistent shared double-entry journal used by every financial workflow. */
internal class SharedLedger(private val prefs: SharedPreferences) {
    private val key = "shared_journal_v1"
    private fun entries() = try { JSONArray(prefs.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
    private fun save(a: JSONArray) { prefs.edit().putString(key, a.toString()).apply() }

    private fun accountClass(name: String): String {
        val n = name.trim().lowercase().replace('ي', 'ی').replace('ك', 'ک')
        // Explicit balance-sheet groups first. Avoid broad substring matches such as "سود"
        // that can misclassify a profit account as equity or an asset as income.
        return when {
            listOf("برگشت از فروش", "فروش کالا", "فروش خدمات", "سایر درآمد", "درآمد", "سود فروش سرمایه گذاری", "سود فروش سرمایه‌گذاری", "سود سرمایه گذاری", "سود سرمایه‌گذاری").any { n.contains(it) } -> "INCOME"
            listOf("برگشت بهای تمام شده فروش", "برگشت بهای تمام‌شده فروش", "هزینه", "بهای تمام", "کارمزد", "زیان", "استهلاک", "حقوق و دستمزد").any { n.contains(it) } -> "EXPENSE"
            listOf("بدهی", "پرداختنی", "وام", "بستانکاران", "مالیات پرداختنی", "اسناد پرداختنی", "تعهدات", "حقوق پرداختنی").any { n.contains(it) } -> "LIABILITY"
            // Investment assets must be classified before the broad word "سرمایه" (equity).
            listOf("سرمایه گذاری", "سرمایه‌گذاری", "صندوق", "بانک", "کیف پول", "مطالبات", "طلب", "اسناد دریافتنی", "موجودی کالا", "دارایی", "پرتفوی", "پیش پرداخت", "پیش‌پرداخت", "ملک", "تجهیزات").any { n.contains(it) } -> "ASSET"
            listOf("سرمایه", "حقوق مالک", "حقوق صاحبان", "سود/زیان انباشته", "سود انباشته", "زیان انباشته", "طرف مقابل مانده افتتاحیه").any { n.contains(it) } -> "EQUITY"
            else -> "ASSET"
        }
    }
    private fun accountId(name: String) = "ACC-" + name.trim().lowercase().hashCode().toUInt().toString(16)
    private fun line(name: String, debit: Long = 0, credit: Long = 0, memo: String = "") =
        JSONObject().put("accountId", accountId(name)).put("accountName", name).put("accountClass", accountClass(name))
            .put("debit", debit).put("credit", credit).put("memo", memo)

    /** Atomic validation + duplicate-source protection; no unbalanced entry is persisted. */
    @Synchronized fun post(sourceType: String, sourceId: String, date: String, description: String, lines: List<JSONObject>): Boolean {
        require(sourceType.isNotBlank() && sourceId.isNotBlank()) { "شناسه رویداد الزامی است" }
        require(lines.size >= 2) { "سند باید حداقل دو ردیف داشته باشد" }
        var d = 0L; var c = 0L
        lines.forEach { l ->
            val debit = l.optLong("debit", -1); val credit = l.optLong("credit", -1)
            require(debit >= 0 && credit >= 0 && ((debit > 0L) xor (credit > 0L))) { "هر ردیف باید دقیقاً یک مبلغ بدهکار یا بستانکار داشته باشد" }
            d = Math.addExact(d, debit); c = Math.addExact(c, credit)
        }
        require(d > 0 && d == c) { "سند نامتوازن است: بدهکار=$d بستانکار=$c" }
        val all = entries()
        for (i in 0 until all.length()) {
            val e = all.optJSONObject(i) ?: continue
            require(!(e.optBoolean("posted", false) && e.optString("sourceType") == sourceType && e.optString("sourceId") == sourceId)) { "این رویداد قبلاً ثبت شده است" }
        }
        all.put(JSONObject().put("id", UUID.randomUUID().toString()).put("date", date).put("description", description)
            .put("sourceType", sourceType).put("sourceId", sourceId).put("lines", JSONArray(lines)).put("debitTotal", d).put("creditTotal", c).put("posted", true))
        save(all); return true
    }

    fun postSimple(type: String, id: String, date: String, title: String, amount: Long, cashAccount: String = "صندوق") {
        require(amount > 0)
        val lines = when (type) {
            "درآمد" -> listOf(line(cashAccount, debit = amount), line("سایر درآمدها", credit = amount))
            "هزینه" -> listOf(line("هزینه‌ها", debit = amount), line(cashAccount, credit = amount))
            else -> throw IllegalArgumentException("نوع تراکنش پشتیبانی نمی‌شود")
        }
        post(type, id, date, title, lines)
    }

    fun postInvoice(r: JSONObject) {
        val type = r.optString("type"); val id = r.optString("invoiceNo").ifBlank { r.optString("id") }
        val amount = r.optLong("total", r.optLong("amount")); require(amount > 0)
        val method = r.optString("paymentMethod"); val party = r.optString("party")
        val cash = r.optString("accountName").ifBlank { "صندوق" }
        val isSales = type == "فروش" || type == "برگشت از فروش"
        val isReturn = type.startsWith("برگشت") || r.optBoolean("isReturn")
        val debitAccount: String; val creditAccount: String
        when {
            isSales && method == "نسیه" -> { debitAccount = "مطالبات از مشتریان${if (party.isBlank()) "" else " - $party"}"; creditAccount = "فروش کالا" }
            isSales && method == "چک" -> { debitAccount = "اسناد دریافتنی"; creditAccount = "فروش کالا" }
            isSales -> { debitAccount = cash; creditAccount = "فروش کالا" }
            method == "نسیه" -> { debitAccount = "موجودی کالا"; creditAccount = "بدهی به تأمین‌کنندگان${if (party.isBlank()) "" else " - $party"}" }
            method == "چک" -> { debitAccount = "موجودی کالا"; creditAccount = "اسناد پرداختنی" }
            else -> { debitAccount = "موجودی کالا"; creditAccount = cash }
        }
        val lines = mutableListOf<JSONObject>()
        if (!isReturn) {
            lines += line(debitAccount, debit = amount)
            lines += line(creditAccount, credit = amount)
        } else {
            // Reverse the base transaction; use dedicated contra accounts for sales/purchase returns.
            if (isSales) {
                lines += line("برگشت از فروش", debit = amount)
                val settlement = when (method) {
                    "نسیه" -> "مطالبات از مشتریان${if (party.isBlank()) "" else " - $party"}"
                    "چک" -> "اسناد دریافتنی"
                    else -> cash
                }
                lines += line(settlement, credit = amount)
            } else {
                val settlement = when (method) {
                    "نسیه" -> "بدهی به تأمین‌کنندگان${if (party.isBlank()) "" else " - $party"}"
                    "چک" -> "اسناد پرداختنی"
                    else -> cash
                }
                lines += line(settlement, debit = amount)
                // Purchases are held in inventory in this app's perpetual-inventory flow.
                // Therefore a purchase return reduces inventory rather than creating income.
                lines += line("موجودی کالا", credit = amount)
            }
        }
        // Inventory cost is posted for sales and sales returns, using the saved per-line unitCost.
        if (type == "فروش" || type == "برگشت از فروش") {
            var cost = 0L; val items = r.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) { val item = items.optJSONObject(i) ?: continue; cost = Math.addExact(cost, Math.multiplyExact(item.optLong("qty").coerceAtLeast(0), item.optLong("unitCost").coerceAtLeast(0))) }
            if (cost > 0L) {
                if (type == "فروش") { lines += line("بهای تمام‌شده کالای فروش‌رفته", debit = cost); lines += line("موجودی کالا", credit = cost) }
                else { lines += line("موجودی کالا", debit = cost); lines += line("برگشت بهای تمام‌شده فروش", credit = cost) }
            }
        }
        post("فاکتور:$type", id, r.optString("date"), r.optString("title", "فاکتور $type"), lines)
    }

    fun postPortfolio(type: String, id: String, date: String, symbol: String, amount: Long, qty: Double, price: Long, buyCost: Long = amount) {
        require(amount > 0)
        val asset = "دارایی پرتفوی - $symbol"
        val lines = if (type == "خرید") listOf(line(asset, debit = amount), line("صندوق", credit = amount)) else {
            val cost = buyCost.coerceAtLeast(0L)
            buildList {
                add(line("صندوق", debit = amount)); add(line(asset, credit = cost))
                if (amount > cost) add(line("سود فروش سرمایه‌گذاری", credit = amount - cost))
                if (cost > amount) add(line("زیان فروش سرمایه‌گذاری", debit = cost - amount))
            }
        }
        post("پرتفوی:$type", id, date, "$type پرتفوی $symbol ($qty × $price)", lines)
    }

    fun postOpening(id: String, date: String, rows: JSONArray) {
        val lines = mutableListOf<JSONObject>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val name = r.optString("account")
            val debit = r.optLong("debit"); val credit = r.optLong("credit")
            if (debit > 0) lines += line(name, debit = debit)
            if (credit > 0) lines += line(name, credit = credit)
        }
        post("افتتاحیه", id, date, "سند افتتاحیه", lines)
    }


    fun postSettlement(id: String, date: String, description: String, cashAccount: String, amount: Long,
                       cashIn: Boolean, clearingAccount: String) {
        require(amount > 0L)
        val lines = if (cashIn) listOf(line(cashAccount, debit = amount), line(clearingAccount, credit = amount))
                    else listOf(line(clearingAccount, debit = amount), line(cashAccount, credit = amount))
        post("تسویه", id, date, description, lines)
    }

    fun postDebtStatus(id: String, date: String, description: String, amount: Long, receivable: Boolean,
                       clearingAccount: String = if (receivable) "مطالبات متفرقه" else "بدهی‌های متفرقه") {
        require(amount > 0L)
        // The counter-entry is explicitly labelled as documented opening equity; never silently plug the balance sheet.
        val lines = if (receivable) listOf(line(clearingAccount, debit = amount), line("طرف مقابل مانده افتتاحیه", credit = amount))
                    else listOf(line("طرف مقابل مانده افتتاحیه", debit = amount), line(clearingAccount, credit = amount))
        post("بدهی-مطالبه", id, date, description, lines)
    }

    fun snapshot(): JSONArray = entries()

    private fun findAccountName(all: JSONArray, id: String): String {
        for (i in 0 until all.length()) {
            val e = all.optJSONObject(i) ?: continue; val ls = e.optJSONArray("lines") ?: continue
            for (j in 0 until ls.length()) { val l = ls.optJSONObject(j) ?: continue; if (l.optString("accountId") == id) return l.optString("accountName", id) }
        }
        return id
    }
    private fun findAccountClass(all: JSONArray, id: String): String {
        for (i in 0 until all.length()) {
            val e = all.optJSONObject(i) ?: continue; val ls = e.optJSONArray("lines") ?: continue
            for (j in 0 until ls.length()) { val l = ls.optJSONObject(j) ?: continue; if (l.optString("accountId") == id) return l.optString("accountClass", "ASSET") }
        }
        return "ASSET"
    }

    /** All financial statements derive exclusively from posted shared-journal lines. */
    fun financialStatement(): JSONObject {
        val tb = trialBalance(); val rows = tb.optJSONArray("rows") ?: JSONArray()
        val accounts = JSONArray(); var assets = 0L; var liabilities = 0L; var equity = 0L
        var income = 0L; var expenses = 0L
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val name = r.optString("accountName"); val cls = r.optString("accountClass")
            val debit = r.optLong("debit"); val credit = r.optLong("credit"); val net = Math.subtractExact(debit, credit)
            accounts.put(JSONObject(r.toString()))
            when (cls) {
                "ASSET" -> assets = Math.addExact(assets, net)
                "LIABILITY" -> liabilities = Math.addExact(liabilities, -net)
                "EQUITY" -> equity = Math.addExact(equity, -net)
                "INCOME" -> income = Math.addExact(income, credit - debit)
                "EXPENSE" -> expenses = Math.addExact(expenses, debit - credit)
            }
        }
        val profit = Math.subtractExact(income, expenses)
        val balanceSheetEquity = Math.addExact(equity, profit)
        return JSONObject().put("accounts", accounts).put("assets", assets).put("liabilities", liabilities)
            .put("equity", balanceSheetEquity).put("equityBeforeCurrentProfit", equity)
            .put("income", income).put("expenses", expenses).put("profit", profit)
            .put("balanceDifference", Math.subtractExact(assets, Math.addExact(liabilities, balanceSheetEquity)))
            .put("totalDebit", tb.optLong("totalDebit")).put("totalCredit", tb.optLong("totalCredit"))
            .put("journalBalanced", tb.optBoolean("balanced"))
    }

    fun trialBalance(): JSONObject {
        val totals = linkedMapOf<String, Pair<Long, Long>>()
        val all = entries()
        for (i in 0 until all.length()) {
            val e = all.optJSONObject(i) ?: continue
            if (!e.optBoolean("posted")) continue
            val ls = e.optJSONArray("lines") ?: continue
            for (j in 0 until ls.length()) {
                val l = ls.optJSONObject(j) ?: continue; val id = l.optString("accountId")
                val old = totals[id] ?: (0L to 0L)
                totals[id] = Math.addExact(old.first, l.optLong("debit")) to Math.addExact(old.second, l.optLong("credit"))
            }
        }
        var debit = 0L; var credit = 0L
        val rows = JSONArray()
        totals.forEach { (id, v) ->
            debit = Math.addExact(debit, v.first); credit = Math.addExact(credit, v.second)
            rows.put(JSONObject().put("accountId", id).put("accountName", findAccountName(all, id)).put("accountClass", findAccountClass(all, id)).put("debit", v.first).put("credit", v.second).put("net", Math.subtractExact(v.first, v.second)))
        }
        return JSONObject().put("rows", rows).put("totalDebit", debit).put("totalCredit", credit).put("balanced", debit == credit)
    }
}
