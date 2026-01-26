package com.sbf.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

@RunWith(AndroidJUnit4::class)
class ToolExecutorTest {

    @Test
    fun sendSmsRequiresPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        revokePermission(context.packageName, android.Manifest.permission.SEND_SMS)
        val executor = ToolExecutor(context, null)
        val args = JSONObject()
            .put("number", "12345")
            .put("message", "hola")
            .toString()
        val result = executor.execute(ToolCall("1", "send_sms", args))
        assertTrue(result.isError)
        assertTrue(result.output.contains("SEND_SMS"))
    }

    @Test
    fun makeCallRequiresPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        revokePermission(context.packageName, android.Manifest.permission.CALL_PHONE)
        val executor = ToolExecutor(context, null)
        val args = JSONObject()
            .put("number", "12345")
            .toString()
        val result = executor.execute(ToolCall("1", "make_call", args))
        assertTrue(result.isError)
        assertTrue(result.output.contains("CALL_PHONE"))
    }

    @Test
    fun getLocationRequiresPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        revokePermission(context.packageName, android.Manifest.permission.ACCESS_FINE_LOCATION)
        val executor = ToolExecutor(context, null)
        val result = executor.execute(ToolCall("1", "get_location", "{}"))
        assertTrue(result.isError)
        assertTrue(result.output.contains("ACCESS_FINE_LOCATION"))
    }

    @Test
    fun unknownToolReturnsError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executor = ToolExecutor(context, null)
        val result = executor.execute(ToolCall("1", "does_not_exist", "{}"))
        assertTrue(result.isError)
        assertTrue(result.output.contains("Tool no reconocida"))
    }

    private fun revokePermission(packageName: String, permission: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val command = "pm revoke $packageName $permission"
        automation.executeShellCommand(command).use { pfd ->
            BufferedReader(InputStreamReader(FileInputStream(pfd.fileDescriptor))).use { reader ->
                while (reader.readLine() != null) {
                    // drain output
                }
            }
        }
    }
}
