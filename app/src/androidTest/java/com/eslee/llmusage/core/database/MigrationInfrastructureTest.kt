package com.eslee.llmusage.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationInfrastructureTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), UsageDatabase::class.java)
    @Test fun versionOneSchemaOpensWithoutDestructiveMigration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("migration-test")
        helper.createDatabase("migration-test", 1).use {
            it.execSQL("INSERT INTO accounts (id,providerId,payload) VALUES ('preserved','grok','{}')")
        }
        val db = Room.databaseBuilder(context, UsageDatabase::class.java, "migration-test").build()
        try {
            val cursor = db.openHelper.writableDatabase.query("SELECT id FROM accounts")
            try {
                check(cursor.moveToFirst() && cursor.getString(0) == "preserved")
            } finally {
                cursor.close()
            }
        } finally {
            db.close()
        }
        context.deleteDatabase("migration-test")
    }
}
