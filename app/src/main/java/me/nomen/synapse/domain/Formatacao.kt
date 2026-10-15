package me.nomen.synapse.domain

import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

private val PT_BR = Locale("pt", "BR")

fun formatarMoeda(valor: Double): String = NumberFormat.getCurrencyInstance(PT_BR).format(valor)

fun hojeIso(): String = LocalDate.now().toString()

/** Aceita "yyyy-MM-dd" (ou prefixo de datetime). Null se inválida. */
fun parseDataIso(texto: String?): LocalDate? =
    texto?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
