import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round




fun main() {
    val people = SplitBillCalculator.parseFormattedInput(
    """
    yusri : simaksi 300000
    rio : logistik 180k, tambahin simaksi 20k
    yeni : ojek 160, makan pagi 135k, makan malem 95k
    naswa : -
    """.trimIndent()
)

val result = SplitBillCalculator.calculate(
    tripName = "Nanjak Sumbing",
    people = people
)

val json = result.toJson()
println(json)


    
}


data class ExpenseInput(
    val label: String,
    val amountRaw: String
)

data class PersonInput(
    val name: String,
    val expenses: List<ExpenseInput>
)

data class ParsedExpense(
    val label: String,
    val amount: Double
)

data class PersonSummary(
    val name: String,
    val expenses: List<ParsedExpense>,
    val total: Double
)

data class Settlement(
    val from: String,
    val to: String,
    val amount: Double
)

data class SplitBillResult(
    val tripName: String?,
    val people: List<PersonSummary>,
    val total: Double,
    val averagePerPerson: Double,
    val settlements: List<Settlement>,
    val finalCheckReceivers: List<String>
) {
    fun toJson(): String {
        return buildString {
            append("{")
            append("\"tripName\":${tripName.asJsonString()},")
            append("\"total\":${SplitBillCalculator.jsonNumber(total)},")
            append("\"averagePerPerson\":${SplitBillCalculator.jsonNumber(averagePerPerson)},")
            append("\"people\":[")
            append(
                people.joinToString(",") { person ->
                    buildString {
                        append("{")
                        append("\"name\":${person.name.asJsonString()},")
                        append("\"total\":${SplitBillCalculator.jsonNumber(person.total)},")
                        append("\"expenses\":[")
                        append(
                            person.expenses.joinToString(",") { expense ->
                                """{"label":${expense.label.asJsonString()},"amount":${SplitBillCalculator.jsonNumber(expense.amount)}}"""
                            }
                        )
                        append("]")
                        append("}")
                    }
                }
            )
            append("],")
            append("\"settlements\":[")
            append(
                settlements.joinToString(",") { settlement ->
                    """{"from":${settlement.from.asJsonString()},"to":${settlement.to.asJsonString()},"amount":${SplitBillCalculator.jsonNumber(settlement.amount)}}"""
                }
            )
            append("],")
            append("\"finalCheckReceivers\":[")
            append(finalCheckReceivers.joinToString(",") { it.asJsonString() })
            append("]")
            append("}")
        }
    }
}

object SplitBillCalculator {
    private val amountRegex = Regex("""(\d+(?:[.,]\d+)?\s*(?:k|rb|ribu|jt|juta)?)""", RegexOption.IGNORE_CASE)

    fun calculate(
        tripName: String? = null,
        people: List<PersonInput>
    ): SplitBillResult {
        val peopleSummaries = people.map { person ->
            val parsedExpenses = person.expenses.map { expense ->
                ParsedExpense(
                    label = expense.label.ifBlank { "Item" },
                    amount = parseAmount(expense.amountRaw)
                )
            }

            PersonSummary(
                name = person.name,
                expenses = parsedExpenses,
                total = parsedExpenses.sumOf { it.amount }
            )
        }

        val total = peopleSummaries.sumOf { it.total }
        val average = if (peopleSummaries.isEmpty()) 0.0 else total / peopleSummaries.size
        val settlements = calculateSettlements(peopleSummaries, average)

        val finalCheckReceivers = peopleSummaries
            .filter { roundCurrency(it.total - average) > 0.009 }
            .map { person ->
                val incoming = settlements
                    .filter { it.to == person.name }
                    .sumOf { it.amount }
                "${person.name} terima ${formatRupiah(roundCurrency(incoming))}"
            }
            .ifEmpty { listOf("semua orang sudah pas di rata-rata.") }

        return SplitBillResult(
            tripName = tripName?.trim()?.takeIf { it.isNotEmpty() },
            people = peopleSummaries,
            total = roundCurrency(total),
            averagePerPerson = roundCurrency(average),
            settlements = settlements,
            finalCheckReceivers = finalCheckReceivers
        )
    }

    fun parseFormattedInput(raw: String): List<PersonInput> {
        return raw
            .split(";")
            .asSequence()
            .map { it.replace(Regex("""\s*\n\s*"""), " ").trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull(::parseFormattedLine)
            .toList()
    }

    fun parseFormattedLine(line: String): PersonInput? {
        val sanitizedLine = line.replace(Regex(""";\s*$"""), "").trim()
        val parts = sanitizedLine.split(":", limit = 2)
        if (parts.size < 2) return null

        val name = parts[0].trim()
        val detail = parts[1].trim()
        if (name.isBlank()) return null

        if (detail.isBlank() || detail == "-" || detail == "0") {
            return PersonInput(name = name, expenses = emptyList())
        }

        val matches = amountRegex.findAll(detail).toList()
        val expenses = matches.mapIndexed { index, match ->
            val start = match.range.first
            val previousEnd = if (index == 0) 0 else matches[index - 1].range.last + 1
            var label = detail
                .substring(previousEnd, start)
                .trim()
                .trim(',', '-', ' ')

            if (index > 0) {
                label = label.removePrefix("dan ").removePrefix("& ").trim()
            }

            val amountRaw = normalizeImportedAmount(match.value)
            ExpenseInput(
                label = if (label.isBlank()) "Item ${index + 1}" else label,
                amountRaw = amountRaw
            )
        }

        return PersonInput(name = name, expenses = expenses)
    }

    fun parseAmount(raw: String?): Double {
        if (raw.isNullOrBlank()) return 0.0

        val cleaned = raw
            .lowercase()
            .replace("rp", "")
            .replace("\\s+".toRegex(), "")
            .replace("\\.(?=\\d{3}\\b)".toRegex(), "")
            .replace(",(\\d+)".toRegex(), ".$1")

        val match = Regex("""(\d+(?:\.\d+)?)(k|rb|ribu|jt|juta)?""").find(cleaned) ?: return 0.0

        var value = match.groupValues[1].toDoubleOrNull() ?: return 0.0
        when (match.groupValues[2]) {
            "k", "rb", "ribu" -> value *= 1_000
            "jt", "juta" -> value *= 1_000_000
        }

        return roundCurrency(value)
    }

    fun normalizeImportedAmount(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return ""

        val compact = text.lowercase().replace("\\s+".toRegex(), "")
        if (compact.any { it.isLetter() }) return text

        if (compact.matches(Regex("""^\d+(?:[.,]\d+)?$"""))) {
            val numericValue = compact.replace(",", ".").toDoubleOrNull()
            if (numericValue != null && numericValue in 0.000001..999.999999) {
                return "${stripTrailingZero(numericValue)}k"
            }
        }

        val digits = text.filter { it.isDigit() }
        return if (digits.isBlank()) text else formatThousands(digits)
    }

    fun formatRupiah(amount: Double): String {
        val rounded = roundCurrency(amount)
        val whole = rounded.toLong()
        val fraction = ((rounded - whole) * 10).toInt()
        return if (abs(rounded - whole) < 0.001) {
            "Rp${formatThousands(whole.toString())}"
        } else {
            "Rp${formatThousands(whole.toString())}.${fraction}"
        }
    }

    private fun calculateSettlements(
        people: List<PersonSummary>,
        average: Double
    ): List<Settlement> {
        data class Balance(
            val name: String,
            var amount: Double
        )

        val creditors = mutableListOf<Balance>()
        val debtors = mutableListOf<Balance>()

        people.forEach { person ->
            val diff = roundCurrency(person.total - average)
            when {
                diff > 0.009 -> creditors += Balance(person.name, diff)
                diff < -0.009 -> debtors += Balance(person.name, abs(diff))
            }
        }

        val settlements = mutableListOf<Settlement>()
        var debtorIndex = 0
        var creditorIndex = 0

        while (debtorIndex < debtors.size && creditorIndex < creditors.size) {
            val amount = roundCurrency(min(debtors[debtorIndex].amount, creditors[creditorIndex].amount))

            settlements += Settlement(
                from = debtors[debtorIndex].name,
                to = creditors[creditorIndex].name,
                amount = amount
            )

            debtors[debtorIndex].amount = roundCurrency(debtors[debtorIndex].amount - amount)
            creditors[creditorIndex].amount = roundCurrency(creditors[creditorIndex].amount - amount)

            if (debtors[debtorIndex].amount <= 0.009) debtorIndex += 1
            if (creditors[creditorIndex].amount <= 0.009) creditorIndex += 1
        }

        return settlements
    }

    private fun roundCurrency(value: Double): Double {
        return round(value * 100) / 100
    }

    private fun formatThousands(digits: String): String {
        return digits
            .reversed()
            .chunked(3)
            .joinToString(".")
            .reversed()
    }

    private fun stripTrailingZero(value: Double): String {
        val longValue = value.toLong()
        return if (abs(value - longValue) < 0.000001) {
            longValue.toString()
        } else {
            value.toString()
        }
    }

    internal fun jsonNumber(value: Double): String {
        val rounded = roundCurrency(value)
        val whole = rounded.toLong().toDouble()
        return if (abs(rounded - whole) < 0.000001) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }
}

private fun String?.asJsonString(): String {
    if (this == null) return "null"

    val escaped = buildString {
        this@asJsonString.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

    return "\"$escaped\""
}
