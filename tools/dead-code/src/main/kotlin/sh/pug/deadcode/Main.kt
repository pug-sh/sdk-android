package sh.pug.deadcode

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.OutputMode
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.origin.Origin
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * `dead-code <aar> <api dump> <allowed> <library jar>...`: shrinks the AAR's classes with R8, keeping
 * only the declarations in the API dump, and fails when R8 removes anything not listed in the
 * allowed file, or when that file lists something R8 keeps. `make check` runs it.
 */
fun main(args: Array<String>) {
    if (args.size < 4) {
        System.err.println("usage: dead-code <aar> <api dump> <allowed> <library jar>...")
        exitProcess(2)
    }
    val (aar, api, allowedFile) = args.take(3).map(::File)
    val libraries = args.drop(3).map { File(it).toPath() }
    val unreachable = unreachable(aar, keepRules(api.readLines()), libraries)
    val allowed = allowedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
    val unexpected = unreachable - allowed
    val stale = allowed - unreachable.toSet()
    if (unexpected.isEmpty() && stale.isEmpty()) return
    if (unexpected.isNotEmpty()) {
        System.err.println("Unreachable from the public API in ${api.name}; delete it, or list it in $allowedFile:")
        unexpected.forEach { System.err.println("  $it") }
    }
    if (stale.isNotEmpty()) {
        System.err.println("Listed in $allowedFile but reachable or gone; remove the line:")
        stale.forEach { System.err.println("  $it") }
    }
    exitProcess(1)
}

private fun unreachable(aar: File, keepRules: List<String>, libraries: List<java.nio.file.Path>): List<String> {
    val work = Files.createTempDirectory("dead-code").toFile()
    try {
        val classes = File(work, "classes.jar")
        ZipFile(aar).use { zip ->
            val entry = zip.getEntry("classes.jar") ?: error("$aar has no classes.jar")
            zip.getInputStream(entry).use { input -> classes.outputStream().use { input.copyTo(it) } }
        }
        val usage = File(work, "usage.txt")
        R8.run(
            R8Command.builder()
                .addProgramFiles(classes.toPath())
                .addLibraryFiles(libraries)
                .addProguardConfiguration(keepRules + RULES + "-printusage ${usage.path}", Origin.unknown())
                .setMode(CompilationMode.RELEASE)
                .setOutput(File(work, "out.jar").toPath(), OutputMode.ClassFile)
                .build(),
        )
        return entries(usage.readLines())
    } finally {
        work.deleteRecursively()
    }
}

// Private members are detekt's (UnusedPrivateMember), which also sees through the private inline
// functions R8 counts as unused. Constants are inlined at compile time, so R8 never sees them used.
private val IGNORED =
    Regex(
        """^\s+(private |.*synthetic |.*static final (boolean|byte|char|short|int|long|float|double|java\.lang\.String) \w+$)""",
    )

// The -printusage format: a class line ending in ':' followed by its removed members, indented, or a
// class line alone when the whole class is removed.
private fun entries(usage: List<String>): List<String> {
    val entries = mutableListOf<String>()
    var owner = ""
    for (line in usage) {
        if (line.isBlank()) continue
        if (!line.startsWith(" ")) {
            owner = line.removeSuffix(":")
            if (!line.endsWith(":")) entries += owner
        } else if (!IGNORED.containsMatchIn(line)) {
            entries += "$owner: ${line.trim()}"
        }
    }
    return entries
}

private val CLASS = Regex("""^(?:public|protected) .*\bclass (\S+)""")
private val MEMBER = Regex("""^\t(?:public|protected) .*?(fun|field) (\S+) """)

private fun keepRules(api: List<String>): List<String> {
    val rules = mutableListOf<String>()
    for (line in api) {
        CLASS.find(line)?.let { rules += "-keep class ${it.groupValues[1].replace('/', '.')} {" }
        MEMBER.find(line)?.let {
            val (kind, name) = it.destructured
            rules += when {
                kind == "field" -> "  *** $name;"
                name == "<init>" -> "  <init>(...);"
                else -> "  *** $name(...);"
            }
        }
        if (line == "}") rules += "}"
    }
    return rules
}

private val RULES =
    listOf(
        // Optimizing would inline live code away and report it as removed.
        "-dontobfuscate",
        "-dontoptimize",
        "-keepattributes *",
        // File facades: keeping the class still reports its unused functions.
        "-keep class **Kt",
        // Members the Kotlin compiler writes whether or not the source uses them: the no-argument
        // constructor beside one whose parameters all have defaults, and data class and enum members.
        "-keepclassmembers class ** { <init>(); *** component*(); *** copy(...); }",
        "-keepclassmembers enum ** { *** values(); *** valueOf(java.lang.String); *** getEntries(); }",
    )
