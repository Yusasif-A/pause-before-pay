package com.pausebeforepay.signal

import kotlin.math.abs

/**
 * Median and MAD rather than mean and standard deviation: one garbage sample
 * should not move a baseline that gates somebody's payment.
 */
object Stats {

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    /** Median absolute deviation — a spread measure that ignores outliers. */
    fun mad(values: List<Double>): Double? {
        val med = median(values) ?: return null
        return median(values.map { abs(it - med) })
    }
}
