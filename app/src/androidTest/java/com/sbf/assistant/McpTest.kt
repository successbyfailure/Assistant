package com.sbf.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpTest {

    @Test
    fun toolNameParsingRoundTrip() {
        val name = McpToolAdapter.composeToolName("filesystem", "read_file")
        val parsed = McpToolAdapter.parseToolName(name)
        assertEquals("mcp.filesystem.read_file", name)
        assertEquals("filesystem", parsed?.serverName)
        assertEquals("read_file", parsed?.toolName)
    }

    @Test
    fun filesystemServerReadWrite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = FileSystemMcpServer(context)
        val path = "mcp_test/hello.txt"

        val writeArgs = JSONObject()
            .put("path", path)
            .put("content", "hola")
        val writeResult = server.callTool("write_file", writeArgs)
        assertFalse(writeResult.isError)

        val readArgs = JSONObject().put("path", path)
        val readResult = server.callTool("read_file", readArgs)
        assertFalse(readResult.isError)
        assertEquals("hola", readResult.content)

        val listArgs = JSONObject().put("path", "mcp_test")
        val listResult = server.callTool("list_files", listArgs)
        assertFalse(listResult.isError)
        assertTrue(listResult.content.contains("hello.txt"))
    }

    @Test
    fun filesystemServerBlocksTraversal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = FileSystemMcpServer(context)
        val readArgs = JSONObject().put("path", "../secret.txt")
        val readResult = server.callTool("read_file", readArgs)
        assertTrue(readResult.isError)
        assertTrue(readResult.content.contains("Ruta invalida"))
    }
}
