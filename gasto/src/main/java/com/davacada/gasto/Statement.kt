package com.davacada.gasto

import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reads a bank statement exported as CSV (Revolut, NLB, N26 and most others):
 * finds the date, amount and description columns by their names, and adds the payments (money going out).
 */
object Statement {
    private val dateCols = Regex("""(?i)^(completed date|started date|date|datum|datum knjiženja|datum transakcije|booking date|value date|transaction date|valuta)""")
    private val amountCols = Regex("""(?i)^(amount|znesek|iznos|betrag|amount \(eur\)|znesek v eur)""")
    private val debitCols = Regex("""(?i)^(debit|breme|v breme|odliv|out|withdrawal|paid out)""")
    private val creditCols = Regex("""(?i)^(credit|dobro|v dobro|priliv|in|deposit|paid in)""")
    private val descCols = Regex("""(?i)^(description|opis|namen|payee|prejemnik|merchant|counterparty|partner|naziv|name|reference|details)""")
    private val stateCols = Regex("""(?i)^(state|status)""")
    private val formats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd", "dd.MM.yyyy HH:mm", "dd.MM.yyyy", "d.M.yyyy", "dd/MM/yyyy", "MM/dd/yyyy", "dd-MM-yyyy")

    private fun split(line: String, sep: Char): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q = false
        for (ch in line) {
            when {
                ch == '"' -> q = !q
                ch == sep && !q -> { out.add(cur.toString().trim()); cur.clear() }
                else -> cur.append(ch)
            }
        }
        out.add(cur.toString().trim())
        return out
    }

    private fun date(s: String): Long? {
        val t = s.trim()
        for (f in formats) runCatching { return SimpleDateFormat(f, Locale.ROOT).apply { isLenient = false }.parse(t)!!.time }
        return null
    }

    /** Returns (added, skipped) or throws with a readable message. */
    fun import(ctx: Context, uri: Uri): Pair<Int, Int> {
        Store.init(ctx)
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw RuntimeException("Couldn't open the file.")
        val text = String(raw, Charsets.UTF_8).removePrefix("﻿").let {
            if (it.contains('�')) String(raw, Charsets.ISO_8859_1) else it
        }
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size < 2) throw RuntimeException("The file looks empty.")
        // the header is the first line that names a date column
        val sepOf = { l: String -> listOf(';', ',', '\t').maxBy { c -> l.count { it == c } } }
        val hIdx = lines.indexOfFirst { l -> split(l, sepOf(l)).any { dateCols.containsMatchIn(it.trim('"')) } }
        if (hIdx < 0) throw RuntimeException("Couldn't find a date column. Export the statement as CSV.")
        val sep = sepOf(lines[hIdx])
        val head = split(lines[hIdx], sep).map { it.trim('"') }
        fun col(rx: Regex) = head.indexOfFirst { rx.containsMatchIn(it) }
        val dC = col(dateCols); val aC = col(amountCols); val deC = col(debitCols); val crC = col(creditCols)
        val descC = col(descCols); val stC = col(stateCols)
        if (aC < 0 && deC < 0) throw RuntimeException("Couldn't find an amount column.")
        var added = 0; var skipped = 0
        for (l in lines.drop(hIdx + 1)) {
            val c = split(l, sep).map { it.trim('"') }
            if (c.size <= maxOf(dC, aC, deC)) { skipped++; continue }
            if (stC >= 0 && c.getOrNull(stC)?.let { Regex("(?i)revert|declin|fail|cancel").containsMatchIn(it) } == true) { skipped++; continue }
            val t = date(c[dC]) ?: run { skipped++; null } ?: continue
            val amount: Double = when {
                deC >= 0 && c.getOrNull(deC).orEmpty().isNotBlank() -> Parse.number(c[deC])?.let { Math.abs(it) }
                aC >= 0 -> Parse.number(c[aC])?.takeIf { it < 0 }?.let { -it }   // money out is negative
                else -> null
            } ?: run { skipped++; null } ?: continue
            if (crC >= 0 && c.getOrNull(crC).orEmpty().isNotBlank() && (deC < 0 || c.getOrNull(deC).isNullOrBlank())) { skipped++; continue }
            val desc = c.getOrNull(descC).orEmpty().ifBlank { "Bank payment" }
            val merchant = Parse.tidy(desc.replace(Regex("""(?i)^(card payment|payment|nakup|plačilo|pos|kartica)[: -]*"""), "").ifBlank { desc })
            val p = Payment(
                id = "csv:${t}:${amount}:${desc.hashCode()}", amount = amount, merchant = merchant, time = t,
                source = "bank", kind = if (Parse.isSub(desc, merchant)) "sub" else "daily",
                category = Parse.category(desc), app = "Statement", note = desc.take(160),
            )
            if (Store.add(ctx, p)) added++ else skipped++
        }
        Alerts.checkBudget(ctx)
        return added to skipped
    }
}
