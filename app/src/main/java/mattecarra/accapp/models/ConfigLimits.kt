package mattecarra.accapp.models

/**
 * The numeric rules the editor and the power-limit dialog enforce, in ONE place that both the
 * production code and the tests can call.
 *
 * They were previously written inline at each UI site and then RE-IMPLEMENTED as private helpers
 * inside AccStateTest. A test that owns its own copy of the rule passes whether or not the real
 * rule still exists: delete the check in PowerLimitDialogExt or change the bound in
 * AccConfigEditorActivity and the suite stays green, which is the opposite of what those tests were
 * added for. Calling the same function from both is what makes them regression tests.
 */
object ConfigLimits {

    /**
     * current_max accepted by ACC. A value above 9999 is refused by the daemon and applies NO
     * limit at all, so the dialog must not enable OK for it - silently applying nothing is worse
     * than refusing, because the user believes a limit is in force.
     */
    fun currentMaxValid(value: Int?): Boolean = (value ?: 0) in 1..9999

    /**
     * shutdown_temp, matching ACC's write-config.sh rule exactly: it sits in
     * [max(max_temp, 40) .. 70]. ACC accepts shutdown == max_temp (50/50), so AccA must too - an
     * earlier max+3 / floor-50 tightening rejected configs the daemon would have taken.
     */
    fun shutdownTempValid(maxTemp: Int, shutdown: Int): Boolean =
        shutdown in maxOf(maxTemp, 40)..70
}
