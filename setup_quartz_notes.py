#!/usr/bin/env python3
import os
import zipfile

FILES = {
    "build.gradle.kts": """plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
}
""",

    "app/build.gradle.kts": """plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.quartz.notes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.quartz.notes"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore (Theme, Gradients & Backup URI persistence)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager (Automatic Scheduled Backups)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // DocumentFile (SAF Directory Access for Backups)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Biometric
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
""",

    "app/src/main/AndroidManifest.xml": """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.USE_BIOMETRIC" />

    <application
        android:allowBackup="true"
        android:label="Quartz Notes"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
""",

    "app/src/main/java/com/quartz/notes/data/local/entity/Entities.kt": """package com.quartz.notes.data.local.entity

import androidx.room.*
import kotlinx.serialization.Serializable

@Serializable
data class ChecklistItem(
    val id: String,
    val text: String,
    val isChecked: Boolean
)

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentFolderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("parentFolderId")]
)
@Serializable
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconName: String = "Folder",
    val parentFolderId: Long? = null,
    val isSystemFolder: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("folderId"), Index("updatedAt"), Index("isPinned"), Index("isTrashed")]
)
@Serializable
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long? = null,
    val title: String = "",
    val contentMarkdown: String = "",
    val checklistJson: String = "[]",
    val isPinned: Boolean = false,
    val isLocked: Boolean = false,
    val isTrashed: Boolean = false,
    val trashedAt: Long? = null,
    val customGradientId: String? = null,
    val customStartColorHex: Long? = null,
    val customEndColorHex: Long? = null,
    val customGradientAngle: Float = 135f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tags")
@Serializable
data class TagEntity(
    @PrimaryKey val name: String
)

@Entity(
    tableName = "note_tag_cross_ref",
    primaryKeys = ["noteId", "tagName"],
    indices = [Index("tagName")]
)
@Serializable
data class NoteTagCrossRef(
    val noteId: Long,
    val tagName: String
)
""",

    "app/src/main/java/com/quartz/notes/data/local/dao/NoteDao.kt": """package com.quartz.notes.data.local.dao

import androidx.room.*
import com.quartz.notes.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query(\"\"\"
        SELECT * FROM notes 
        WHERE isTrashed = 0 
        AND (:folderId IS NULL OR folderId = :folderId)
        ORDER BY isPinned DESC, updatedAt DESC
    \"\"\")
    fun getActiveNotes(folderId: Long? = null): Flow<List<NoteEntity>>

    @Query(\"\"\"
        SELECT * FROM notes 
        WHERE isTrashed = 0 
        AND (title LIKE '%' || :query || '%' OR contentMarkdown LIKE '%' || :query || '%')
        ORDER BY isPinned DESC, updatedAt DESC
    \"\"\")
    fun searchNotes(query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE isTrashed = 1 ORDER BY trashedAt DESC")
    fun getTrashedNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM