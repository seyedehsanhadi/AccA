package mattecarra.accapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import mattecarra.accapp.utils.isPlainAccCommand

class ScriptSafetyTest {
    @Test fun seededQuickActionsAllPass() {
        listOf("acca -sk", "acca --early-cap", "acca --diag", "acca -sb", "acca -ss::", "acca -D",
               "acc -d", "acc -e", "acca -s ui_refresh=60")
            .forEach { assertTrue(it, isPlainAccCommand(it)) }
    }

    @Test fun shellMetacharactersAreBlocked() {
        listOf("acc -d; id > /sdcard/pwn", "acc -d && rm -rf /data", "acca -s x=\$(id)",
               "acca -s x=`id`", "acc -d | sh", "acc -d\nid", "sh /data/local/tmp/x.sh",
               "echo hi", "acca -s x='a b'", "acc -d > /sdcard/out")
            .forEach { assertFalse(it, isPlainAccCommand(it)) }
    }

    @Test fun bareCommandWithNoArgsIsNotEnough() =
        assertFalse(isPlainAccCommand("acc"))
}
