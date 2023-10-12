package org.hurricup.profiler.perf

import com.intellij.openapi.project.Project
import com.intellij.profiler.api.ProfilerDumpParserProvider

class PerfScriptProfilerParserProvider : ProfilerDumpParserProvider {
  override val id = "perf.script.parser"
  override val name = "perf script dump"
  override val requiredFileExtension = "script"

  override fun createParser(project: Project) = PerfScriptProfilerParser()
}