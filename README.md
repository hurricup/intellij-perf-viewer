# Intellij [`perf`](https://perf.wiki.kernel.org/index.php/Main_Page) Snapshots Viewer 
This is a reader for the script representation of the [`perf`](https://perf.wiki.kernel.org/index.php/Main_Page) snapshots 
in Intellij profiler.

![image](https://github.com/hurricup/intellij-perf-viewer/assets/2811330/9170d0a1-d1bd-49be-9d0f-44f3288707e1)


# Disclaimer
- I created this plugin by the way, it was not the main goal, I just needed a nice viewer. 
- It is provided as is. 
- I may fix things, but currently there are no plans to continue development.
- It works only in commercial products from JetBrains (ones have profiling support)
- Some symbols may be unknown (looks like `Interpreter+0x....`). This is caused by incomplete perfmap from JVM, not sure why.

# How to make a JVM app snapshot to load

1. Start your java process with 
   ```
   -XX:+PreserveFramePointer 
   -XX:+UnlockDiagnosticVMOptions 
   -XX:+DebugNonSafepoints
   ```
2. Sample the app. Following samples with 500 hz, `$PID` or `$TID` for 60 seconds, recording output to the `perf.data`:
    ```bash
    sudo perf record -F 500 -p $PID [-t $TID] -g -o perf.data -- sleep 60
    ```
    Additional options can be found on the [`perf` wiki](https://perf.wiki.kernel.org/index.php/Main_Page).
4. Create a map for java calls and perf addresses, **BEFORE finishing the process**:
    ```bash
    jcmd $PID Compiler.perfmap
    ```
    Alternatively you can run process with `-XX:+DumpPerfMapAtExit`
4. Create a plain text report (going to be pretty large):
    ```bash
    perf script -i perf.data > perf.script
    ```
5. Load `perf.script` with `Open Profiler Snapshot` action.
