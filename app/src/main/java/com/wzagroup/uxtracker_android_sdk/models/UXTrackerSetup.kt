package com.wzagroup.uxtracker_android_sdk.models

data class UXTrackerSetup(
    val flushInterval: Long = 30,
    val batchSize: Int = 20
)