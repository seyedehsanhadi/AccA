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
     * shutdown_temp, matching ACC's write-config.sh rule exactly: it sits in [40 .. 70], and it
     * carries no max_temp term.
     *
     * The floor used to be max(max_temp, 40), which mirrored an ACC setter that rejected any
     * cutoff below max_temp. That rule was removed after a field report - "47C and shutdown_temp 45
     * did not fire", because `acc -s shutdown_temp=45` against the default max_temp 50 stored 55 and
     * said nothing. The daemon never had the restriction: _temp_shutdown_check band-checks 40..70
     * with no max_temp term at all. Keeping the old floor here would leave AccA refusing to save
     * exactly the configuration the daemon now accepts and the reporter asked for.
     *
     * The two limits are independent. max_temp pauses CHARGING; this one powers the phone off
     * whatever the cable is doing, so "power off at 45, pause charging at 50" is coherent - the
     * pause is simply never reached.
     */
    fun shutdownTempValid(maxTemp: Int, shutdown: Int): Boolean =
        shutdown in 40..70
}
