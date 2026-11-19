package com.davacada.gasto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

/** One payment Gasto found. */
data class Payment(
    val id: String,
    var amount: Double,
    var merchant: String,
    val time: Long,
    val source: String,          // bank, wallet, gmail, play, manual
    var kind: String,            // sub or daily
    var category: String,        // food, transport, out, other
    val app: String = "",
    val note: String = "",
) {
    fun json() = JSONObject().put("id", id).put("amount", amount).put("merchant", merchant).put("time", time)
        .put("source", source).put("kind", kind).put("category", category).put("app", app).put("note", note)

    companion object {
        fun from(o: JSONObject) = Payment(o.getString("id"), o.getDouble("amount"), o.getString("merchant"), o.getLong("time"),
            o.optString("source"), o.optString("kind", "daily"), o.optString("category", "other"), o.optString("app"), o.optString("note"))
    }
}

data class Sub(val merchant: String, val amount: Double, val last: Long, val next: Long, val source: String)

object Store {
    private var loaded = false
    private val list = ArrayList<Payment>()
    private val listeners = ArrayList<() -> Unit>()
    private lateinit var file: File

    fun init(ctx: Context) {
        if (loaded) return
        file = File(ctx.filesDir, "payments.json")
        if (file.exists()) runCatching {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) list.add(Payment.from(a.getJSONObject(i)))
        }
        loaded = true
    }

    @Synchronized fun all(): List<Payment> = list.sortedByDescending { it.time }

    @Synchronized fun add(ctx: Context, p: Payment): Boolean {
        init(ctx)
        if (list.any { it.id == p.id }) return false
        // The same payment often shows twice (Google Wallet and the bank): same amount within 5 minutes.
        if (p.source != "manual" && list.any { Math.abs(it.amount - p.amount) < 0.005 && Math.abs(it.time - p.time) < 5 * 60_000 && it.source != "manual" }) return false
        // A Gmail receipt for something the bank already told us about (same amount, same day).
        if ((p.source == "gmail" || p.source == "play") && list.any {
                Math.abs(it.amount - p.amount) < 0.005 && Math.abs(it.time - p.time) < 36 * 3600_000L && it.source in setOf("bank", "wallet") }) {
            // keep the better name and kind from the receipt
            list.first { Math.abs(it.amount - p.amount) < 0.005 && Math.abs(it.time - p.time) < 36 * 3600_000L }.apply {
                if (p.kind == "sub") kind = "sub"
                if (merchant.length < 3 || merchant == app) merchant = p.merchant
            }
            save(); return false
        }
        // A statement line for a payment the bank already notified about (statements often only have the day).
        if (p.id.startsWith("csv:") && list.any { !it.id.startsWith("csv:") && Math.abs(it.amount - p.amount) < 0.005 && Math.abs(it.time - p.time) < 36 * 3600_000L }) return false
        list.add(p)
        save()
        return true
    }

    @Synchronized fun update(ctx: Context, p: Payment) { init(ctx); save() }
    @Synchronized fun remove(ctx: Context, p: Payment) { init(ctx); list.removeAll { it.id == p.id }; save() }

    private fun save() {
        val a = JSONArray()
        list.sortedByDescending { it.time }.take(3000).forEach { a.put(it.json()) }
        runCatching { file.writeText(a.toString()) }
        listeners.toList().forEach { it() }
    }

    fun listen(f: () -> Unit) { listeners.add(f) }
    fun unlisten(f: () -> Unit) { listeners.remove(f) }
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("gasto", Context.MODE_PRIVATE)
    fun base(ctx: Context) = p(ctx).getFloat("base", 1270f).toDouble()
    fun setBase(ctx: Context, v: Double) = p(ctx).edit().putFloat("base", v.toFloat()).apply()
    fun startDay(ctx: Context) = p(ctx).getInt("startDay", 10)   // payday
    fun setStartDay(ctx: Context, d: Int) = p(ctx).edit().putInt("startDay", d).apply()
    fun bool(ctx: Context, k: String, def: Boolean) = p(ctx).getBoolean(k, def)
    fun setBool(ctx: Context, k: String, v: Boolean) = p(ctx).edit().putBoolean(k, v).apply()
    fun str(ctx: Context, k: String) = p(ctx).getString(k, null)
    fun setStr(ctx: Context, k: String, v: String?) = p(ctx).edit().putString(k, v).apply()
    fun long(ctx: Context, k: String) = p(ctx).getLong(k, 0L)
    fun setLong(ctx: Context, k: String, v: Long) = p(ctx).edit().putLong(k, v).apply()

    /** Apps whose notifications count as payments, and apps we've seen mention money. */
    fun apps(ctx: Context): MutableSet<String> = p(ctx).getStringSet("apps", null)?.toMutableSet() ?: mutableSetOf()
    fun setApps(ctx: Context, s: Set<String>) = p(ctx).edit().putStringSet("apps", s).apply()
    fun seen(ctx: Context): MutableSet<String> = p(ctx).getStringSet("seen", null)?.toMutableSet() ?: mutableSetOf()
    fun setSeen(ctx: Context, s: Set<String>) = p(ctx).edit().putStringSet("seen", s).apply()
}

/** The current month, starting on the chosen day. */
object Period {
    fun start(ctx: Context, now: Long = System.currentTimeMillis()): Long {
        val d = Prefs.startDay(ctx)
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.get(Calendar.DAY_OF_MONTH) < d) c.add(Calendar.MONTH, -1)
        c.set(Calendar.DAY_OF_MONTH, Math.min(d, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        return c.timeInMillis
    }
    fun end(ctx: Context, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply { timeInMillis = start(ctx, now); add(Calendar.MONTH, 1) }
        return c.timeInMillis
    }
    fun daysLeft(ctx: Context): Int {
        val ms = end(ctx) - System.currentTimeMillis()
        return Math.max(1, Math.ceil(ms / 86_400_000.0).toInt())
    }
    fun payments(ctx: Context): List<Payment> {
        val s = start(ctx); val e = end(ctx)
        return Store.all().filter { it.time in s until e }
    }
    fun monthName(ctx: Context): String {
        val c = Calendar.getInstance().apply { timeInMillis = start(ctx) + 3 * 86_400_000L }
        return c.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.ENGLISH) ?: ""
    }
}

object Subs {
    /** One line per subscription: the latest charge, next one a month later. */
    fun list(): List<Sub> {
        val subs = Store.all().filter { it.kind == "sub" }
        return subs.groupBy { it.merchant.lowercase().trim() }.map { (_, ps) ->
            val last = ps.maxBy { it.time }
            val c = Calendar.getInstance().apply { timeInMillis = last.time; add(Calendar.MONTH, 1) }
            while (c.timeInMillis < System.currentTimeMillis() - 86_400_000L) c.add(Calendar.MONTH, 1)
            Sub(last.merchant, last.amount, last.time, c.timeInMillis, last.source)
        }.filter { System.currentTimeMillis() - it.last < 70L * 86_400_000L }   // gone quiet for 2+ months = probably cancelled
            .sortedBy { it.next }
    }
    fun monthly() = list().sumOf { it.amount }
}

/** Reading amounts, shop names and categories out of text. */
object Parse {
    private val amountRx = listOf(
        Regex("""(?:€|EUR)\s?([-−]?\d{1,3}(?:[.\s ]\d{3})*(?:[.,]\d{1,2})?|[-−]?\d+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""([-−]?\d{1,3}(?:[.\s ]\d{3})*(?:[.,]\d{1,2})|[-−]?\d+(?:[.,]\d{1,2})?)\s?(?:€|EUR)""", RegexOption.IGNORE_CASE),
    )

    fun number(s: String): Double? {
        var t = s.replace("−", "-").replace(" ", "").replace(" ", "").trim()
        val neg = t.startsWith("-"); t = t.removePrefix("-")
        // 1.234,56 or 1,234.56 or 23,84 or 23.84
        val lastComma = t.lastIndexOf(','); val lastDot = t.lastIndexOf('.')
        t = when {
            lastComma > lastDot -> t.replace(".", "").replace(',', '.')
            lastDot > lastComma && lastComma >= 0 -> t.replace(",", "")
            lastDot >= 0 && t.length - lastDot == 4 && lastComma < 0 -> t.replace(".", "")   // 1.234
            else -> t
        }
        val v = t.toDoubleOrNull() ?: return null
        return if (neg) -v else v
    }

    /** All euro amounts in the text, with their position. */
    fun amounts(text: String): List<Pair<Int, Double>> {
        val out = ArrayList<Pair<Int, Double>>()
        for (rx in amountRx) for (m in rx.findAll(text)) {
            val v = number(m.groupValues[1]) ?: continue
            if (Math.abs(v) in 0.01..100_000.0 && out.none { Math.abs(it.first - m.range.first) < 4 }) out.add(m.range.first to v)
        }
        return out.sortedBy { it.first }
    }

    private val incoming = Regex("""prejel|prejem|priliv|nakazilo na|incoming|received|refund|vračil|vracil|credited|top.?up|polog|deposit|cashback|salary|plača""", RegexOption.IGNORE_CASE)
    private val spending = Regex("""paid|payment|purchase|spent|card|debit|transaction|charged|plačil|placil|nakup|kartic|bremenitev|transakcij|odliv|potrdilo|zaračun|you paid|tap|contactless""", RegexOption.IGNORE_CASE)

    fun isSpend(text: String) = spending.containsMatchIn(text) && !incoming.containsMatchIn(text)

    private val merchantRx = listOf(
        Regex("""(?:\bat|\bpri trgovcu|\bpri|\bto|\bv trgovini|\bmerchant:?|\btrgovec:?|\bprejemnik:?)\s+([A-Z0-9ČŠŽ][\wČŠŽčšž .&'*/-]{1,32}?)(?=[,.;:]?\s|\s*$|,|\.|\(|€|\d{1,3}[.,]\d{2})"""),
    )

    fun merchant(text: String, fallback: String): String {
        for (rx in merchantRx) rx.find(text)?.let { m ->
            val s = m.groupValues[1].trim().trimEnd('.', ',', '-', '*')
            if (s.length >= 2 && !s.matches(Regex("""\d+.*"""))) return tidy(s)
        }
        return tidy(fallback)
    }

    fun tidy(s: String): String {
        var t = s.replace(Regex("""\s{2,}"""), " ").replace(Regex("""\*+"""), " ").trim()
        t = t.replace(Regex("""(?i)\b(d\.o\.o\.|d\.d\.|s\.p\.|ltd|gmbh|inc\.?)\b"""), "").trim().trimEnd(',', '.')
        if (t == t.uppercase() && t.length > 3) t = t.lowercase().split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        return t.take(32)
    }

    private val cats = listOf(
        "food" to Regex("""lidl|mercator|spar|hofer|tuš|tus\b|eurospin|jager|wolt|glovo|ehrana|restavr|restaurant|pizz|burger|mcdonald|kebab|bistro|pekarn|bakery|market|food|hrana|kfc|subway|dm\b|müller|muller""", RegexOption.IGNORE_CASE),
        "transport" to Regex("""bolt|uber|petrol|omv|mol\b|shell|urbana|lpp|parking|parkiri|avant2go|bicikelj|sharengo|flixbus|arriva|nomago|slovenske železnice|sz\b|taxi|cab|fuel|gorivo|vinjet|dars|ryanair|wizz""", RegexOption.IGNORE_CASE),
        "out" to Regex("""bar\b|pub|kavarna|caffe|cafe|coffee|kino|cinema|cineplexx|club|klub|concert|koncert|ticket|vstopnic|eventim|bowling|pivnica|beer|cocktail""", RegexOption.IGNORE_CASE),
    )

    fun category(text: String): String = cats.firstOrNull { it.second.containsMatchIn(text) }?.first ?: "other"

    private val subWords = Regex("""subscription|renew|recurring|membership|monthly plan|per month|/month|/mo\b|naročnin|narocnin|mesečn|mesecn|abonma|premium|plus plan|auto-?pay|trial ended|next billing|next payment""", RegexOption.IGNORE_CASE)
    private val subSenders = Regex("""netflix|spotify|youtube|google one|disney|hbo|max\.com|apple\.com|icloud|canva|adobe|chatgpt|openai|anthropic|claude|microsoft|xbox|playstation|crunchyroll|patreon|dropbox|notion|figma|deezer|tidal|a1\b|telemach|telekom|t-2|t2\b|hot\b|fitnes|gym|amazon prime|audible|duolingo|github""", RegexOption.IGNORE_CASE)

    fun isSub(text: String, sender: String = "") = subWords.containsMatchIn(text) || subSenders.containsMatchIn(sender)
}
