package net.wpm.llvm;

import static org.bytedeco.llvm.global.LLVM.LLVMDisposeMessage;
import static org.bytedeco.llvm.global.LLVM.LLVMInitializeNativeAsmParser;
import static org.bytedeco.llvm.global.LLVM.LLVMInitializeNativeAsmPrinter;
import static org.bytedeco.llvm.global.LLVM.LLVMInitializeNativeTarget;
import static org.bytedeco.llvm.global.LLVM.LLVMPrintMessageAction;
import static org.bytedeco.llvm.global.LLVM.LLVMVerifyModule;

import java.io.FileNotFoundException;
import java.net.URISyntaxException;
import java.text.ParseException;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.llvm.LLVM.LLVMErrorRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMOrcJITStackRef;
import org.bytedeco.llvm.LLVM.LLVMOrcLazyCompileCallbackFn;
import org.bytedeco.llvm.LLVM.LLVMOrcSymbolResolverFn;
import org.bytedeco.llvm.LLVM.LLVMTargetMachineRef;
import org.bytedeco.llvm.LLVM.LLVMTargetRef;
import org.bytedeco.llvm.global.LLVM;


/**
 * Reference
 * https://www.doof.me.uk/2017/05/11/using-orc-with-llvms-c-api/
 * https://github.com/microsoft/llvm/blob/master/unittests/ExecutionEngine/Orc/OrcCAPITest.cpp#L179
 * 
 * @author Nico Hezel
 *
 */
public class LLVMOrcDirectCallCompiler {

	public static void main(String[] args) throws URISyntaxException, FileNotFoundException, ParseException {
		
		// Initialize the compiler
		initialize();
		
		// Should we bail out for windows platforms. Since it might not be supported?
		// https://github.com/llvm-mirror/llvm/blob/master/unittests/ExecutionEngine/Orc/OrcTestCommon.h#L139
		final BytePointer def_triple = LLVM.LLVMGetDefaultTargetTriple();

		// Get target from default triple
		final BytePointer error_str = new BytePointer(); 
		final LLVMTargetRef target_ref = new LLVMTargetRef(); 
		if(LLVM.LLVMGetTargetFromTriple(def_triple, target_ref, error_str) != 0) {
			System.out.printf("Creating target from triple failed: %s \n", error_str.getString());
			LLVMDisposeMessage(def_triple);
			LLVMDisposeMessage(error_str);
			return;
		}

		// Check if JIT is available
		if(LLVM.LLVMTargetHasJIT(target_ref) == 0) {
			System.out.printf("Cannot do JIT on this platform");
			LLVMDisposeMessage(def_triple);
			return;
		}
		
		System.out.println("Target information triplet: " + def_triple.getString());
		System.out.println("Target machine name: " + LLVM.LLVMGetTargetName(target_ref).getString());
		System.out.println("Target machine description: " + LLVM.LLVMGetTargetDescription(target_ref).getString());

		// Create target machine
		int optimization_level = 3;
		LLVMTargetMachineRef tm_ref = LLVM.LLVMCreateTargetMachine(target_ref, 
				def_triple.getString(),
				"", 
				"",
				optimization_level,
				LLVM.LLVMRelocDefault,
				LLVM.LLVMCodeModelJITDefault);
		LLVMDisposeMessage(def_triple);
		LLVMOrcJITStackRef orc_ref = LLVM.LLVMOrcCreateInstance(tm_ref);
        
		// Mangle the given symbol.
		// Memory is allocated for the mangled symbol, which will be owned by the client.
        LLVMFacModuleBuilder moduleBuilder = new LLVMFacModuleBuilder();
		BytePointer funcName = new BytePointer();
		LLVM.LLVMOrcGetMangledSymbol(orc_ref, funcName, moduleBuilder.getFunctionName());
		System.out.println("Mangled symbol name: "+funcName.getString());
		
		// Find the and return the symbol/function address of the provided name.
		// The lookupCtx is the same object as the last parameter in the LLVMOrcAddEagerlyCompiledIR we call next.
		LLVMOrcSymbolResolverFn symbol_resolver_callback = new LLVMOrcSymbolResolverFn() {
            @Override 
            public long call(BytePointer name, Pointer lookupCtx) {
            	System.out.println("LLVMOrcSymbolResolverFn: "+name.getString());
            	return 0L; // dummy address
            }
        };
        
        LLVMOrcLazyCompileCallbackFn lazyCompileCallback = new LLVMOrcLazyCompileCallbackFn() {
			public long call(LLVMOrcJITStackRef JITStack, Pointer CallbackCtx) {
				System.out.println("LLVMOrcLazyCompileCallbackFn");
				
				// build and verify the module
				LLVMModuleRef module = moduleBuilder.build();
				verify(module);
				
				// TODO eagerly compile does not work
				long[] retHandle = new long[1];
//				LLVMErrorRef errorCode = LLVM.LLVMOrcAddEagerlyCompiledIR(JITStack, retHandle, module, symbol_resolver_callback, CallbackCtx);
				LLVMErrorRef errorCode = LLVM.LLVMOrcAddLazilyCompiledIR(JITStack, retHandle, module, symbol_resolver_callback, CallbackCtx);
				checkOrcError(orc_ref, errorCode);
				
			    long[] fnAddr = new long[1];
			    errorCode = LLVM.LLVMOrcGetSymbolAddressIn(JITStack, fnAddr, retHandle[0], funcName);
				checkOrcError(orc_ref, errorCode);
				System.out.println(funcName.getString()+" function address: "+fnAddr[0]);
				
				errorCode = LLVM.LLVMOrcSetIndirectStubPointer(JITStack, funcName + "_stub", fnAddr[0]);
				checkOrcError(orc_ref, errorCode);
				
				return fnAddr[0];
			}
		};
		
		long[] retHandle = new long[1];
		LLVMErrorRef errorCode = LLVM.LLVMOrcCreateLazyCompileCallback(orc_ref, retHandle, lazyCompileCallback, orc_ref);
		checkOrcError(orc_ref, errorCode);
		System.out.println("retHandle value: " + retHandle[0]);
		
		errorCode = LLVM.LLVMOrcCreateIndirectStub(orc_ref, funcName + "_stub", retHandle[0]);
		checkOrcError(orc_ref, errorCode);
		
		// get symbol address searching the entire stack
		long[] fnAddr = new long[1];
		errorCode = LLVM.LLVMOrcGetSymbolAddress(orc_ref, fnAddr, funcName + "_stub");
		checkOrcError(orc_ref, errorCode);
		System.out.println("Stub-function address: "+fnAddr[0]);

		// try to call the function
		com.sun.jna.Function func = com.sun.jna.Function.getFunction(new com.sun.jna.Pointer(fnAddr[0]));
		Object result = func.invoke(int.class, new Object[] {10});
		System.out.println("result: "+result);
		
		System.out.println("finished");
	}

	/**
	 * https://github.com/llvm-mirror/llvm/blob/master/unittests/ExecutionEngine/Orc/OrcTestCommon.h#L73
	 */
	protected static void initialize() {

		// The main program should call this function to initialize the printer for the native target corresponding to the host.
		LLVMInitializeNativeAsmPrinter();

		// The main program should call this function to initialize the parser for the native target corresponding to the host.
		LLVMInitializeNativeAsmParser();

		// The main program should call this function to initialize the native target corresponding to the host.
		LLVMInitializeNativeTarget();
	}
	
	protected static void checkOrcError(LLVMOrcJITStackRef orc_ref, LLVMErrorRef errorCode) {
		if(errorCode != null) {

			if(LLVM.LLVMErrorSuccess != errorCode.asByteBuffer().getInt()) {
				BytePointer errorMsg = LLVM.LLVMOrcGetErrorMsg(orc_ref);
				try {
					throw new RuntimeException(errorMsg.getString());
				} finally {
					LLVMDisposeMessage(errorMsg);
				}
			}
		}
	}

	protected static void verify(LLVMModuleRef module) {
		BytePointer error = new BytePointer((Pointer) null);
		try {
			if (LLVMVerifyModule(module, LLVMPrintMessageAction, error) != 0) {
				throw new RuntimeException(error.getString());
			}
		} finally {
			LLVMDisposeMessage(error);
		}
	}
}
