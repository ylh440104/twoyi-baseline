// Export a decompiled listing from Ghidra headless.
//
// Runs inside Ghidra as a post-script:
//   analyzeHeadless <proj> <name> -import <binary> -postScript ExportDecompiled.java
//
// Output path comes from the DECOMP_OUT environment variable; the script writes
// function signatures first, then the pseudo-C for each function, then the
// cross references of every string that looks relevant to booting the guest.

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

import java.io.FileWriter;
import java.io.PrintWriter;

public class ExportDecompiled extends GhidraScript {

    @Override
    public void run() throws Exception {
        String outPath = System.getenv("DECOMP_OUT");
        if (outPath == null || outPath.isEmpty()) {
            outPath = "/tmp/decompiled.txt";
        }
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);

        try (PrintWriter out = new PrintWriter(new FileWriter(outPath))) {
            out.println("// program: " + currentProgram.getName());
            out.println("// image base: " + currentProgram.getImageBase());
            out.println();

            int functionCount = 0;
            FunctionIterator functions = currentProgram.getFunctionManager().getFunctions(true);
            while (functions.hasNext() && !monitor.isCancelled()) {
                Function function = functions.next();
                functionCount++;
                long size = function.getBody().getNumAddresses();
                out.println("// ==== " + function.getName() + " @ " + function.getEntryPoint()
                        + " size=" + size
                        + " params=" + function.getParameterCount());
            }
            out.println("// total functions: " + functionCount);
            out.println();

            out.println("// ---------- strings and cross references ----------");
            DataIterator data = currentProgram.getListing().getDefinedData(true);
            while (data.hasNext() && !monitor.isCancelled()) {
                Data item = data.next();
                if (!(item.getValue() instanceof String)) {
                    continue;
                }
                String text = (String) item.getValue();
                if (text.isEmpty() || text.length() < 4) {
                    continue;
                }
                ReferenceIterator references = currentProgram.getReferenceManager()
                        .getReferencesTo(item.getAddress());
                StringBuilder refs = new StringBuilder();
                while (references.hasNext()) {
                    Reference reference = references.next();
                    Address from = reference.getFromAddress();
                    Function caller = currentProgram.getFunctionManager()
                            .getFunctionContaining(from);
                    refs.append("\n//     <- ").append(from)
                            .append(caller == null ? "" : " in " + caller.getName());
                }
                if (refs.length() > 0) {
                    out.println("// \"" + text.replace("\n", "\\n") + "\"" + refs);
                }
            }
            out.println();

            out.println("// ---------- decompiled functions ----------");
            FunctionIterator decompileTargets = currentProgram.getFunctionManager().getFunctions(true);
            while (decompileTargets.hasNext() && !monitor.isCancelled()) {
                Function function = decompileTargets.next();
                DecompileResults results = decompiler.decompileFunction(function, 60, monitor);
                out.println("// ==== " + function.getName() + " @ " + function.getEntryPoint());
                if (results != null && results.decompileCompleted()) {
                    out.println(results.getDecompiledFunction().getC());
                } else {
                    out.println("// (decompilation failed)");
                }
                out.println();
            }
            out.println("// done");
        } finally {
            decompiler.dispose();
        }
    }
}