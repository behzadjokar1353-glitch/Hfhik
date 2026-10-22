package ir.behzad.accounting

/**
 * Core double-entry accounting primitives. Amounts are integer minor units (the app's selected
 * currency unit); no floating point is used for journal arithmetic.
 * Every journal entry must balance before it can be accepted.
 */
internal enum class AccountClass { ASSET, LIABILITY, EQUITY, INCOME, EXPENSE }

internal data class LedgerAccount(
    val id: String,
    val name: String,
    val accountClass: AccountClass,
    val active: Boolean = true
)

internal data class JournalLine(
    val accountId: String,
    val debit: Long = 0L,
    val credit: Long = 0L,
    val memo: String = ""
) {
    init {
        require(debit >= 0L && credit >= 0L) { "مبلغ بدهکار/بستانکار نمی‌تواند منفی باشد" }
        require((debit > 0L) xor (credit > 0L)) { "هر ردیف باید دقیقاً یک سمت مبلغ‌دار داشته باشد" }
    }
}

internal data class JournalEntry(
    val id: String,
    val date: String,
    val description: String,
    val sourceType: String,
    val sourceId: String,
    val lines: List<JournalLine>,
    val posted: Boolean = true
)

internal data class AccountBalance(val debit: Long, val credit: Long) {
    val netDebit: Long get() = Math.subtractExact(debit, credit)
}

internal data class LedgerReport(
    val balances: Map<String, AccountBalance>,
    val totalDebits: Long,
    val totalCredits: Long,
    val isBalanced: Boolean
)

internal object AccountingEngine {
    fun validate(entry: JournalEntry, accounts: Map<String, LedgerAccount>) {
        require(entry.id.isNotBlank()) { "شناسه سند الزامی است" }
        require(entry.date.isNotBlank()) { "تاریخ سند الزامی است" }
        require(entry.lines.size >= 2) { "سند باید حداقل دو ردیف داشته باشد" }
        entry.lines.forEach { line ->
            val account = accounts[line.accountId]
                ?: throw IllegalArgumentException("حساب ${line.accountId} تعریف نشده است")
            require(account.active) { "حساب ${account.name} غیرفعال است" }
        }
        val debit = entry.lines.fold(0L) { sum, line -> Math.addExact(sum, line.debit) }
        val credit = entry.lines.fold(0L) { sum, line -> Math.addExact(sum, line.credit) }
        require(debit > 0L && credit > 0L) { "هر دو طرف سند باید مبلغ داشته باشند" }
        require(debit == credit) { "سند متوازن نیست؛ بدهکار=$debit و بستانکار=$credit" }
    }

    /** Duplicate source IDs are rejected so retries cannot post the same business event twice. */
    fun post(
        entry: JournalEntry,
        accounts: Map<String, LedgerAccount>,
        existingEntries: List<JournalEntry>
    ): List<JournalEntry> {
        validate(entry, accounts)
        require(existingEntries.none {
            it.posted && it.sourceType == entry.sourceType && it.sourceId == entry.sourceId
        }) { "برای این رویداد قبلاً سند ثبت شده است" }
        return existingEntries + entry.copy(posted = true)
    }

    fun report(entries: List<JournalEntry>, accounts: Map<String, LedgerAccount>): LedgerReport {
        val mutable = mutableMapOf<String, Pair<Long, Long>>()
        var totalDebit = 0L
        var totalCredit = 0L
        entries.filter { it.posted }.forEach { entry ->
            validate(entry, accounts)
            entry.lines.forEach { line ->
                val old = mutable[line.accountId] ?: (0L to 0L)
                mutable[line.accountId] = Math.addExact(old.first, line.debit) to
                    Math.addExact(old.second, line.credit)
                totalDebit = Math.addExact(totalDebit, line.debit)
                totalCredit = Math.addExact(totalCredit, line.credit)
            }
        }
        val balances = mutable.mapValues { AccountBalance(it.value.first, it.value.second) }
        return LedgerReport(balances, totalDebit, totalCredit, totalDebit == totalCredit)
    }

    /** Normal-side balance, represented as a signed value in the account's normal direction. */
    fun normalBalance(account: LedgerAccount, balance: AccountBalance): Long {
        val debitNormal = account.accountClass == AccountClass.ASSET ||
            account.accountClass == AccountClass.EXPENSE
        val signed = Math.subtractExact(balance.debit, balance.credit)
        return if (debitNormal) signed else Math.negateExact(signed)
    }

    /** Balance-sheet equation check; equity must be posted from evidence, not silently plugged. */
    fun balanceSheetDifference(assets: Long, liabilities: Long, equity: Long): Long =
        Math.subtractExact(assets, Math.addExact(liabilities, equity))
}
