package net.wpm.llvm;

import static org.bytedeco.llvm.global.LLVM.LLVMAddFunction;
import static org.bytedeco.llvm.global.LLVM.LLVMAddIncoming;
import static org.bytedeco.llvm.global.LLVM.LLVMAppendBasicBlock;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildBr;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildCall;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildCondBr;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildICmp;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildMul;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildPhi;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildRet;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildSub;
import static org.bytedeco.llvm.global.LLVM.LLVMCCallConv;
import static org.bytedeco.llvm.global.LLVM.LLVMConstInt;
import static org.bytedeco.llvm.global.LLVM.LLVMCreateBuilder;
import static org.bytedeco.llvm.global.LLVM.LLVMDisposeBuilder;
import static org.bytedeco.llvm.global.LLVM.LLVMFunctionType;
import static org.bytedeco.llvm.global.LLVM.LLVMGetParam;
import static org.bytedeco.llvm.global.LLVM.LLVMInt32Type;
import static org.bytedeco.llvm.global.LLVM.LLVMIntEQ;
import static org.bytedeco.llvm.global.LLVM.LLVMModuleCreateWithName;
import static org.bytedeco.llvm.global.LLVM.LLVMPositionBuilderAtEnd;
import static org.bytedeco.llvm.global.LLVM.LLVMSetFunctionCallConv;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.LLVM.LLVMBuilderRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

public class LLVMFacModuleBuilder {

	protected final LLVMTypeRef llvmInt32Type;
	protected final String functionName  = "fac";

	public LLVMFacModuleBuilder() {
		llvmInt32Type = LLVMInt32Type();
	}

	public String getFunctionName() {
		return functionName;
	}

	public LLVMModuleRef build() {

		LLVMModuleRef module = LLVMModuleCreateWithName("fac_module");

		LLVMTypeRef[] args = { llvmInt32Type };
		LLVMTypeRef funcType = LLVMFunctionType(llvmInt32Type, new PointerPointer<>(args), 1, 0);
		LLVMValueRef fac = LLVMAddFunction(module, functionName, funcType);
		LLVMSetFunctionCallConv(fac, LLVMCCallConv);

		LLVMValueRef n = LLVMGetParam(fac, 0);

		LLVMBasicBlockRef entry = LLVMAppendBasicBlock(fac, "entry");
		LLVMBasicBlockRef iftrue = LLVMAppendBasicBlock(fac, "iftrue");
		LLVMBasicBlockRef iffalse = LLVMAppendBasicBlock(fac, "iffalse");
		LLVMBasicBlockRef end = LLVMAppendBasicBlock(fac, "end");
		LLVMBuilderRef builder = LLVMCreateBuilder();

		LLVMPositionBuilderAtEnd(builder, entry);
		LLVMValueRef If = LLVMBuildICmp(builder, LLVMIntEQ, n, LLVMConstInt(llvmInt32Type, 0, 0), "n == 0");
		LLVMBuildCondBr(builder, If, iftrue, iffalse);

		LLVMPositionBuilderAtEnd(builder, iftrue);
		LLVMValueRef res_iftrue = LLVMConstInt(llvmInt32Type, 1, 0);
		LLVMBuildBr(builder, end);

		LLVMPositionBuilderAtEnd(builder, iffalse);
		LLVMValueRef n_minus = LLVMBuildSub(builder, n, LLVMConstInt(llvmInt32Type, 1, 0), "n - 1");
		LLVMValueRef call_fac = LLVMBuildCall(builder, fac, n_minus, 1, new BytePointer("fac(n - 1)"));
		LLVMValueRef res_iffalse = LLVMBuildMul(builder, n, call_fac, "n * fac(n - 1)");
		LLVMBuildBr(builder, end);

		LLVMPositionBuilderAtEnd(builder, end);
		LLVMValueRef res = LLVMBuildPhi(builder, llvmInt32Type, "result");
		LLVMValueRef[] phi_vals = { res_iftrue, res_iffalse };
		LLVMBasicBlockRef[] phi_blocks = { iftrue, iffalse };
		LLVMAddIncoming(res, new PointerPointer<>(phi_vals), new PointerPointer<>(phi_blocks), 2);

		LLVMBuildRet(builder, res);

		LLVMDisposeBuilder(builder);

		return module;
	}
}
