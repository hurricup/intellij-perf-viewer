package org.hurricup.profiler.perf

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.NlsSafe
import com.intellij.profiler.DummyCallTreeBuilder
import com.intellij.profiler.LineByLineParser
import com.intellij.profiler.api.*
import com.intellij.profiler.model.NativeThread
import com.intellij.profiler.model.ThreadInfo
import com.intellij.profiler.ui.NativeCallStackElementRenderer.Companion.INSTANCE
import com.intellij.util.containers.Interner
import java.io.File

/**
 * Parser for the output of `perf script -i intput.data`.
 * To create a script following command sequence should be run:
 * ```
 * # Start java process with -XX:+PreserveFramePointer -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints
 *
 * # sample the app
 * # Following samples with 500 hz, $PID or $TID for 60 seconds, recording output to the perf.data
 * sudo perf record -F 500 -p $PID [-t $TID] -g -o perf.data -- sleep 60
 *
 * # create a map for java calls and perf addresses, JIT stuff.  *BEFORE finishing the process*. Alternatively run process with -XX:+DumpPerfMapAtExit
 * jcmd $PID Compiler.perfmap
 *
 * # create plain text report with
 * perf script -i perf.data > perf.script
 * ```
 */

private val log: Logger = Logger.getInstance(PerfScriptProfilerParser::class.java)
private val spacesRegex = Regex("\\s+")

/**
 *  Captures: address, name and file parts.
 *  ```
 *  f1918c0ec04 void com.intellij.util.indexing.FileBasedIndexImpl$$Lambda$4423/0x0000000801d35368.run()+0xc4 (/tmp/perf-88428.map)
 *  ```
 */
private val stackLineRegex = Regex("^\\s+(\\S+)\\s+(.+)\\s+\\(([^)]+)\\)$")

/**
 * Captures class and method name parts from.
 * ```
 * com.intellij.psi.tree.IElementType org.jetbrains.plugins.ruby.ruby.lang.parser.parsing.controlStructures.Case.parse(org.jetbrains.plugins.ruby.ruby.lang.parser.parsingUtils.RBuilder)+0x202c
 * ```
 */
private val javaFramePattern = Regex("^(?:[^.]+\\.)*([^.]+\\.[^.]+)\\(")

/**
 * These are frames where mappings are missing from the JVM
 */
private val unknownFramePrefix = "Interpreter+0x";

class PerfScriptProfilerParser : LineByLineParser(), ProfilerDumpFileParser {
  override val helpId = null
  private val myCallTreeBuilder = DummyCallTreeBuilder<BaseCallStackElement>()
  private val myStringInterner: Interner<String> = Interner.createStringInterner()
  private val myThreadInfoInterner: MutableMap<String, ThreadInfo> = hashMapOf()
  private val myFrameInterner: MutableMap<String, PerfStackFrameElement> = hashMapOf()

  private var myBadLines: Long = 0
  private var myCurrentThreadState: PerfThreadState? = null

  @NlsSafe
  private var myError: String? = null

  override fun parse(file: File, indicator: ProgressIndicator): ProfilerDumpFileParsingResult {
    readLargeFile(file, indicator)
    return myError?.let { Failure(myError!!) } ?: Success(
      NewCallTreeOnlyProfilerData(myCallTreeBuilder, INSTANCE))
  }

  override fun consumeLine(line: String) {
    if (myError != null) {
      return
    }
    if (line.isEmpty()) {
      // end of stacktrace
      if (myCurrentThreadState == null) {
        return
      }
      myCallTreeBuilder.addStack(myCurrentThreadState!!.threadInfo, myCurrentThreadState!!.stack.reversed(), 1)
      myCurrentThreadState = null
      return
    }

    if (myCurrentThreadState == null) {
      // beginning of the new stacktrace
      computeThread(line)
      return
    }

    // stack frame
    myCurrentThreadState!!.stack.add(myFrameInterner.computeIfAbsent(line) { computeFrame(line) })
  }

  /**
   * Attempts to compute a single stack element from the perf output line.
   * Currently supported:
   * ```
   * f1918c0ec04 void com.intellij.util.indexing.FileBasedIndexImpl$$Lambda$4423/0x0000000801d35368.run()+0xc4 (/tmp/perf-88428.map)
   * ```
   */
  private fun computeFrame(line: String): PerfStackFrameElement {
    val matchResult = stackLineRegex.matchEntire(line)
    if (matchResult == null) {
      val trimmedLine = myStringInterner.intern(line.trim())
      return PerfStackFrameElement("", trimmedLine, "")
    }
    val groupValues = matchResult.groupValues

    val isUnknownFile = groupValues[2].startsWith(unknownFramePrefix)

    val frameName = if (isUnknownFile) "${groupValues[2]} ${groupValues[1]}"
    else javaFramePattern.matchAt(groupValues[2], 0)?.let { it.groupValues[1] }
         ?: groupValues[2]

    val isMappingFile = groupValues[3].endsWith(".map")
    val fileName = if (isUnknownFile || !isMappingFile) groupValues[3] else ""

    return PerfStackFrameElement(
      myStringInterner.intern(groupValues[1]), myStringInterner.intern(frameName), myStringInterner.intern(fileName))
  }

  /**
   * Accepts following headers
   * ```
   * Indexing   88428/88707   14182.263881:         16 cycles:P:
   * Indexing   88707   14182.263881:         16 cycles:P:
   * ```
   */
  private fun computeThread(line: String) {
    if (line.startsWith('\t')) {
      // bad line from bad stackframe
      return badLine("Skipping line (starts with tab): ", line)
    }
    val chunks = line.split(spacesRegex)
    if (chunks.size < 3) {
      return badLine("Stack header expected to have at least 3 fields: Name, Pid/Tid and time: ", line)
    }

    val (threadName, frameTid, textTime) = chunks
    val tid = if (frameTid.contains("/")) frameTid.split('/')[1] else frameTid

    val trimmedTime = textTime.trimEnd(':')

    val time = if (trimmedTime.contains(".")) {
      trimmedTime.toFloatOrNull()?.let { floatTime -> (floatTime * 1000000).toLong() }
    }
               else {
      trimmedTime.toLongOrNull()
    } ?: return badLine("Unable to parse stack time: ", line)

    val threadInfo = myThreadInfoInterner.computeIfAbsent(tid) {
      NativeThread(myStringInterner.intern(tid), myStringInterner.intern("$threadName-$tid"))
    }
    myCurrentThreadState = PerfThreadState(threadInfo, time)
  }

  private fun badLine(reason: String, line: String) {
    log.debug(reason, line)
    myBadLines++
  }

  inner class PerfThreadState(val threadInfo: ThreadInfo, val time: Long) {
    val stack: MutableList<PerfStackFrameElement> = mutableListOf()
  }
}

class PerfStackFrameElement(val offset: String, val name: String, val file: String) : BaseCallStackElement() {
  override fun fullName() = if (file.isEmpty()) name else "$name ($file)"
}