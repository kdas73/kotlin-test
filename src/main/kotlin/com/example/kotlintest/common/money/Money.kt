package com.example.kotlintest.common.money

/**
 * The project money format (single currency).
 *
 * - In JSON, money is a string with a decimal amount in major units and exactly 2 fraction digits (`"50.00"`).
 * - In code and in the database, money is a `Long` number of minor units (cents): `"50.00"` <-> `5000`.
 *
 * Conversion uses only string and integer arithmetic with overflow checks. `Double`, `Float` and floating-point
 * JSON numbers are never used for money.
 */
object Money {

    /** Pattern of a money string in a request: no sign, no extra leading zeros, exactly 2 fraction digits. */
    const val REQUEST_PATTERN = "^(0|[1-9][0-9]*)\\.[0-9]{2}$"

    private val requestRegex = Regex(REQUEST_PATTERN)

    private const val MINOR_UNITS_PER_MAJOR = 100L

    /** True if [text] is a valid request money string that fits into a `Long` number of minor units. */
    fun isValid(text: String): Boolean = runCatching { parse(text) }.isSuccess

    /**
     * Parses a request money string (for example `"10.05"`) into minor units (`1005`).
     *
     * @throws IllegalArgumentException if [text] does not match [REQUEST_PATTERN]
     * @throws ArithmeticException if the amount does not fit into a `Long`
     */
    fun parse(text: String): Long {
        require(requestRegex.matches(text)) { "Invalid money amount: '$text'" }
        val dot = text.indexOf('.')
        val major = text.substring(0, dot).toLongOrNull()
            ?: throw ArithmeticException("Money amount out of range: '$text'")
        val minor = text.substring(dot + 1).toLong()
        return Math.addExact(Math.multiplyExact(major, MINOR_UNITS_PER_MAJOR), minor)
    }

    /** Formats minor units (`-1505`) as a money string (`"-15.05"`). */
    fun format(minorUnits: Long): String {
        // Work on the negated value for negative amounts so Long.MIN_VALUE does not overflow.
        val negative = minorUnits < 0
        val major = minorUnits / MINOR_UNITS_PER_MAJOR
        val minor = minorUnits % MINOR_UNITS_PER_MAJOR
        val majorDigits = if (negative) major.toString().removePrefix("-") else major.toString()
        val minorDigits = (if (negative) -minor else minor).toString().padStart(2, '0')
        return (if (negative) "-" else "") + majorDigits + "." + minorDigits
    }
}
