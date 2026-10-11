package sh.pug.abidump

import java.io.File
import java.nio.file.Files
import java.util.ServiceLoader
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess
import org.jetbrains.kotlin.abi.tools.api.AbiFilters
import org.jetbrains.kotlin.abi.tools.api.AbiToolsFactory
import org.jetbrains.kotlin.abi.tools.api.AbiToolsInterface

/**
 * `abi-dump [--check] <aar> <dump>`: writes the public API of the AAR's classes to the dump file, in
 * the format of the Kotlin Gradle plugin's ABI validation (and BCV); with `--check`, fails when the
 * file differs instead. `make abi-dump` and `make check` run it.
 */
fun main(args: Array<String>) {
    val check = args.firstOrNull() == "--check"
    val paths = if (check) args.drop(1) else args.asList()
    if (paths.size != 2) {
        System.err.println("usage: abi-dump [--check] <aar> <dump>")
        exitProcess(2)
    }
    val (aar, dumpFile) = paths.map(::File)
    val tools = ServiceLoader.load(AbiToolsFactory::class.java).first().get()
    val actual = dump(tools, aar)
    if (!check) {
        dumpFile.parentFile.mkdirs()
        dumpFile.writeText(actual)
        println("Wrote $dumpFile")
        return
    }
    if (dumpFile.isFile && dumpFile.readText() == actual) return
    val fresh = File.createTempFile("abi-dump", ".api")
    try {
        fresh.writeText(actual)
        if (dumpFile.isFile) System.err.println(tools.filesDiff(dumpFile, fresh))
    } finally {
        fresh.delete()
    }
    System.err.println("The public API differs from $dumpFile: run 'make abi-dump', review the diff and commit it")
    exitProcess(1)
}

private fun dump(tools: AbiToolsInterface, aar: File): String {
    val classes = Files.createTempDirectory("abi-dump").toFile()
    try {
        ZipFile(aar).use { zip ->
            val jar = zip.getEntry("classes.jar") ?: error("$aar has no classes.jar")
            ZipInputStream(zip.getInputStream(jar)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                    val file = File(classes, entry.name)
                    check(file.canonicalPath.startsWith(classes.canonicalPath + File.separator)) { entry.name }
                    file.parentFile.mkdirs()
                    file.outputStream().use { input.copyTo(it) }
                }
            }
        }
        val files = classes.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
        return StringBuilder().also { tools.v2.printJvmDump(it, files, AbiFilters.EMPTY) }.toString()
    } finally {
        classes.deleteRecursively()
    }
}
