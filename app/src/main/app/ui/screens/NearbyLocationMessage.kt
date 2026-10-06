package com.starglean.app.ui.screens

import com.starglean.app.core.discovery.NearbyLocationIssue
import com.starglean.app.core.discovery.NearbyLocationProblem
import com.starglean.app.core.discovery.NearbyReadingPolicy
import kotlin.math.ceil

internal fun unreliableLocationMessage(issue: NearbyLocationIssue?): String = when (issue?.problem) {
    null -> "Collecting a fresh location fix…"
    NearbyLocationProblem.AccuracyMissing ->
        "This location fix does not report accuracy. Try for a fresh location fix."
    NearbyLocationProblem.AccuracyTooWide -> {
        val reported = issue.accuracyMeters?.takeIf { it.isFinite() && it >= 0.0 }
        val detail = reported?.let { "Its reported accuracy is about ${ceil(it).toLong()} m. " }.orEmpty()
        detail + "Starglean needs a reported accuracy of " +
            "${NearbyReadingPolicy.MAX_HORIZONTAL_ACCURACY_METERS.toInt()} m or better."
    }
    NearbyLocationProblem.InvalidCoordinates ->
        "This location fix contains invalid coordinates. Try for a fresh location fix."
    NearbyLocationProblem.InvalidTimestamp ->
        "This location fix has an invalid timestamp. Try for a fresh location fix."
    NearbyLocationProblem.MockLocation ->
        "Simulated locations cannot be used here. Use a fresh device location."
    NearbyLocationProblem.StaleSample ->
        "This location fix is too old. Try for a fresh location fix."
}
