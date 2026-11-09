package com.example.kotlintest.config

import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

/**
 * SQLite has no date/time type. Timestamps are stored as ISO-8601 UTC `TEXT` with exactly 3 fraction digits
 * (`2026-11-08T12:00:00.123Z`). The fixed width keeps the text sortable in the same order as the instants.
 * Extra precision below milliseconds is truncated.
 */
object InstantTextFormat {
    val FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
}

@WritingConverter
object InstantToTextConverter : Converter<Instant, String> {
    override fun convert(source: Instant): String = InstantTextFormat.FORMATTER.format(source)
}

@ReadingConverter
object TextToInstantConverter : Converter<String, Instant> {
    override fun convert(source: String): Instant = Instant.parse(source)
}
