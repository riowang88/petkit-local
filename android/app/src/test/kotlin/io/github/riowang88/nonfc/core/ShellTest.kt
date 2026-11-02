package io.github.riowang88.nonfc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellTest {
    @Test
    fun dfIsReadFromTheRightAndSurvivesAWrappedName() {
        val text = """
            Filesystem           1K-blocks      Used Available Use% Mounted on
            /dev/root                10240     10240         0 100% /
            /dev/mtdblock7-with-a-very-long-name
                                     16384     11361      5023  69% /system
        """.trimIndent()
        assertEquals(5023L * 1024, Shell.availableBytes(text, "/system"))
    }

    @Test
    fun unknownMountIsUnknownNotFull() {
        assertNull(Shell.availableBytes("df: /system: No such file or directory", "/system"))
    }

    @Test
    fun wcSkipsErrorsAndTotal() {
        val text = "  1024 /system/app_init.sh\nwc: /system/x: No such file\n1024 total"
        assertEquals(mapOf("/system/app_init.sh" to 1024L), Shell.sizes(text))
    }
}
