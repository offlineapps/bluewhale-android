@file:JvmName("Log")

package android.util

fun d(tag: String, msg: String): Int {
    println("DEBUG: $tag: $msg")
    return 0;
}

fun e(tag: String, msg: String): Int {
    println("ERROR: $tag: $msg")
    return 0;
}

fun w(tag: String, msg: String): Int {
    println("WARN: $tag: $msg")
    return 0;
}

fun v(tag: String, msg: String): Int {
    println("VERBOSE: $tag: $msg")
    return 0;
}

fun i(tag: String, msg: String): Int {
    println("INFO: $tag: $msg")
    return 0;
}
// Overloads with a throwable, so main code that logs an exception on a decode failure does not
// hit NoSuchMethodError in plain JUnit tests
fun e(tag: String, msg: String, tr: Throwable?): Int {
    println("ERROR: $tag: $msg ${tr?.message ?: ""}")
    return 0
}

fun w(tag: String, msg: String, tr: Throwable?): Int {
    println("WARN: $tag: $msg ${tr?.message ?: ""}")
    return 0
}

fun d(tag: String, msg: String, tr: Throwable?): Int {
    println("DEBUG: $tag: $msg ${tr?.message ?: ""}")
    return 0
}
