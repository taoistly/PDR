
package edu.nus

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.*
import me.tongfei.progressbar.ProgressBar
import java.io.File

object PDMEGA : CliktCommand() {
    val threads by option(help = "Threads to use [default: CPU core]").int().default(Runtime.getRuntime().availableProcessors())
    val K by option("-k", help = "Block size [default: 1000]").int().default(1000)
//    val cc50 by option(help = "calculate CC50 [default: false]").flag()
//    val output by option("-o", help = "Output file path").file().default(File("DesirableOut.fasta"))
    val tmpDir by option("-d", help = "Temporary folder for intermediate files [default: PDRTmp]").file(mustExist = false).default(File("PDRTmp"))
    val debugTmpDir by option("--debug", help = "Keep temporary folder for debugging purpose").flag(default = false)
    val reference by argument(help = "Reference genome").file(mustExist = true)
    val assembly by argument(help = "Assembly to evaluate").file(mustExist = true)
    val aligner by option("-a", help = "Executable path of aligner (BWA or minimap2) [default: bwa]").default("bwa")
    val joinError by option("-e", help = "Maximum offset for two alignment segment to be jointed [default: 0]").int().default(0)
    val reportLength by option("-m", help = "Minimum chromosome length (in bp) to summarize and report alignment statistics. This doesn't change PDR result. [default: 1% genome]").int().default(-1)

    private fun bwa(refBlock: File, totalBin: Int):File {
        val sam = File(tmpDir,"RefToAsm.sam")
        if (File(assembly.path+".sa").exists()){
            println("[Info] index file for ${assembly.path} exists, use it")
        } else {
            println("[CommandLine] $aligner index ${assembly.path}")
            Runtime.getRuntime().exec("$aligner index ${assembly.path}").errorStream.bufferedReader().forEachLine { println(it) }
        }
        println("[CommandLine] $aligner mem -t $threads ${assembly.path} ${refBlock.path} -o ${sam.path}")
        val sm = Runtime.getRuntime().exec("$aligner mem -t $threads ${assembly.path} ${refBlock.path} -o ${sam.path}")
        val pb = ProgressBar("BWA", totalBin.toLong())
        sm.errorStream.bufferedReader().forEachLine {
            val match = "Processed (\\d+) reads".toRegex().find(it)
            if (match!=null) pb.stepBy(match.groupValues[1].toLong())
        }
        sm.waitFor()
        pb.close()
        return sam
    }
    private fun minimap2(refBlock: File, totalBin: Int):File {
        val sam = File(tmpDir,"RefToAsm.sam")
        val cmd = "$aligner -t $threads -a -x sr ${assembly.path} ${refBlock.path}"
        println("[CommandLine] $cmd")
        val sm = ProcessBuilder(cmd.split(" "))
        sm.redirectOutput(sam)
        val proc = sm.start()
        val pb = ProgressBar("Minimap2", totalBin.toLong())
        proc.errorStream.bufferedReader().forEachLine {
            val match = "mapped (\\d+) sequences".toRegex().find(it)
            if (match!=null) pb.stepBy(match.groupValues[1].toLong())
        }
        proc.waitFor()
        pb.close()
        return sam
    }
    override fun run() {
        println("--- settings ---\n  Thread: $threads\n  Bin size: $K\n  Alignment: $aligner\n  Joint within: $joinError\n")
        val runtime = kotlin.system.measureTimeMillis {
            tmpDir.mkdirs()
            println("====== Reference Breaking ======")
            val refBlock = File(tmpDir,"ReferenceBlock.fasta")
            val refInfo = FastaSplitter(reference).chopAndWriteFasta(K,refBlock)
            val totalBin = refInfo.sumBy { it.binCount }
            println("[Info] Successfully finished\n")
            println("====== Alignment ======")
            val sam = if (aligner.endsWith("bwa")) bwa(refBlock, totalBin) else minimap2(refBlock, totalBin)
            println("[Info] successfully finished\n")
            println("====== Mapping Analysis ======")
            val mappingAnalyzer = MappingAnalyzer(sam, refInfo)
            val pdr = mappingAnalyzer.run()
            println("[Info] successfully finished\n")
            println("====== Result ======")
            println("PDR = $pdr")
            println("    = %.2f%%\n".format(pdr*100))
            if (debugTmpDir) tmpDir.deleteRecursively()
        }
        println("Elapsed time: ${runtime/60000}m${runtime/1000%60}s")
    }
}

fun main(args: Array<String>) = PDMEGA.main(if (args.isEmpty()) arrayOf("--help") else args)
