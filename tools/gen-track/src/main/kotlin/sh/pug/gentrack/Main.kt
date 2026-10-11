package sh.pug.gentrack

import java.io.File
import kotlin.system.exitProcess
import org.json.JSONObject

/**
 * `gen-track <image.json> <source root>`: writes the SDK's typed-track sources under the Kotlin source
 * root from a `buf build` JSON image of the event protos. `make typed-track` runs it.
 */
fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("usage: gen-track <image.json> <source root>")
        exitProcess(2)
    }
    val events =
        try {
            readEvents(JSONObject(File(args[0]).readText()))
        } catch (e: CatalogException) {
            System.err.println("gen-track: ${e.message}")
            exitProcess(1)
        }
    for ((path, source) in render(events)) {
        val file = File(args[1], path)
        file.writeText(source)
        println("Wrote $file")
    }
    println("${events.size} well-known events")
}
