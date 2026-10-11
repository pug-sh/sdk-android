package sh.pug.android.internal

// Parameter null-checks are compiled out (PLAN.md D20), so a Java caller can hand a non-null
// parameter a null. Taking Any? keeps the compiler from reading the check as always false.
internal fun isNull(value: Any?): Boolean = value == null
