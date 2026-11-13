package io.github.riowang88.nonfc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueTest {
    @Test
    fun `the script is what follows the marker`() {
        val output = "-rwxr-xr-x 1 root root 13 /system/app_init.sh\n${Rescue.PRESENT}\n{\"result\":{}}"
        assertEquals("{\"result\":{}}", Rescue.script(output))
        assertNull(Rescue.script("ls: /system/app_init.sh*: No such file or directory"))
    }

    @Test
    fun `a stub that never starts the stock init is the brick`() {
        // What a 200-with-default-reply download left on a real T6.
        assertEquals(Rescue.Verdict.NO_STOCK_INIT, Rescue.verdict("{\"result\":{}}"))
        assertEquals(Rescue.Verdict.NO_STOCK_INIT, Rescue.verdict(""))
    }

    @Test
    fun `a healthy wrapper and no script are left alone`() {
        assertEquals(Rescue.Verdict.HEALTHY, Rescue.verdict(BootScript.withSsh(null)))
        assertEquals(Rescue.Verdict.MISSING, Rescue.verdict(null))
    }

    @Test
    fun `someone else's script that still boots is not ours to judge`() {
        assertEquals(Rescue.Verdict.FOREIGN, Rescue.verdict("#!/bin/sh\n. /app/script/app_init.sh\n"))
    }

    @Test
    fun `the reboot is detached from the reply`() {
        assertTrue(Rescue.MOVE_ASIDE.endsWith("{ (sleep 1; reboot) >/dev/null 2>&1 & }"))
        assertTrue("echo MOVED" in Rescue.MOVE_ASIDE)
    }
}
