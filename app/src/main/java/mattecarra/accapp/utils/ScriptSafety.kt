package mattecarra.accapp.utils

private val PLAIN_ACC_COMMAND = Regex("^acca? [A-Za-z0-9_:.=-]+( [A-Za-z0-9_:.=-]+)*$")

fun isPlainAccCommand(body: String): Boolean =
    PLAIN_ACC_COMMAND.matches(body.trim())
