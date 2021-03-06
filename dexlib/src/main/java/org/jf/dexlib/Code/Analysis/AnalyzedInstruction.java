/*
 * [The "BSD licence"]
 * Copyright (c) 2010 Ben Gruver (JesusFreke)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.jf.dexlib.Code.Analysis;

import org.jf.dexlib.Code.*;
import org.jf.dexlib.Item;
import org.jf.dexlib.ItemType;
import org.jf.dexlib.MethodIdItem;
import org.jf.dexlib.Util.ExceptionWithContext;

import java.util.*;

public class AnalyzedInstruction implements Comparable<AnalyzedInstruction> {
    /**
     * The actual instruction
     */
    protected Instruction instruction;

    /**
     * The index of the instruction, where the first instruction in the method is at index 0, and so on
     */
    protected final int instructionIndex;

    /**
     * Instructions that can pass on execution to this one during normal execution
     */
    protected final TreeSet<AnalyzedInstruction> predecessors = new TreeSet<AnalyzedInstruction>();

    /**
     * Instructions that can execution could pass on to next during normal execution
     */
    protected final LinkedList<AnalyzedInstruction> successors = new LinkedList<AnalyzedInstruction>();

    /**
     * This contains the register types *before* the instruction has executed
     */
    protected final RegisterType[] preRegisterMap;

    /**
     * This contains the register types *after* the instruction has executed
     */
    protected final RegisterType[] postRegisterMap;

    /**
     * When deodexing, we might need to deodex this instruction multiple times, when we merge in new register
     * information. When this happens, we need to restore the original (odexed) instruction, so we can deodex it again
     */
    protected final Instruction originalInstruction;

    /**
     * An analyzed instruction's "deadness" is set during analysis (i.e. MethodAnalyzer.analyzer()). A dead instruction
     * is one that the analyzer never reaches. This occurs either with natural "dead code" - code that simply has no
     * code path that can ever reach it, or code that follows an odexed instruction that can't be deodexed.
     */
    protected boolean dead = false;

    public AnalyzedInstruction(Instruction instruction, int instructionIndex, int registerCount) {
        this.instruction = instruction;
        this.originalInstruction = instruction;
        this.instructionIndex = instructionIndex;
        this.postRegisterMap = new RegisterType[registerCount];
        this.preRegisterMap = new RegisterType[registerCount];
        RegisterType unknown = RegisterType.getRegisterType(RegisterType.Category.Unknown, null);
        for (int i=0; i<registerCount; i++) {
            preRegisterMap[i] = unknown;
            postRegisterMap[i] = unknown;
        }
    }

    public int getInstructionIndex() {
        return instructionIndex;
    }

    public int getPredecessorCount() {
        return predecessors.size();
    }

    public SortedSet<AnalyzedInstruction> getPredecessors() {
        return Collections.unmodifiableSortedSet(predecessors);
    }

    protected boolean addPredecessor(AnalyzedInstruction predecessor) {
        return predecessors.add(predecessor);
    }

    protected void addSuccessor(AnalyzedInstruction successor) {
        successors.add(successor);
    }

    protected void setDeodexedInstruction(Instruction instruction) {
        assert originalInstruction.opcode.odexOnly();
        this.instruction = instruction;
    }

    protected void restoreOdexedInstruction() {
        assert originalInstruction.opcode.odexOnly();
        instruction = originalInstruction;
    }

    public int getSuccessorCount() {
        return successors.size();
    }

    public List<AnalyzedInstruction> getSuccesors() {
        return Collections.unmodifiableList(successors);
    }

    public Instruction getInstruction() {
        return instruction;
    }

    public Instruction getOriginalInstruction() {
        return originalInstruction;
    }

    public boolean isDead() {
        return dead;
    }

    /**
     * Is this instruction a "beginning instruction". A beginning instruction is defined to be an instruction
     * that can be the first successfully executed instruction in the method. The first instruction is always a
     * beginning instruction. If the first instruction can throw an exception, and is covered by a try block, then
     * the first instruction of any exception handler for that try block is also a beginning instruction. And likewise,
     * if any of those instructions can throw an exception and are covered by try blocks, the first instruction of the
     * corresponding exception handler is a beginning instruction, etc.
     *
     * To determine this, we simply check if the first predecessor is the fake "StartOfMethod" instruction, which has
     * an instruction index of -1.
     * @return a boolean value indicating whether this instruction is a beginning instruction
     */
    public boolean isBeginningInstruction() {
        //if this instruction has no predecessors, it is either the fake "StartOfMethod" instruction or it is an
        //unreachable instruction.
        if (predecessors.size() == 0) {
            return false;
        }

        if (predecessors.first().instructionIndex == -1) {
            return true;
        }
        return false;
    }

    /*
     * Merges the given register type into the specified pre-instruction register, and also sets the post-instruction
     * register type accordingly if it isn't a destination register for this instruction
     * @param registerNumber Which register to set
     * @param registerType The register type
     * @returns true If the post-instruction register type was changed. This might be false if either the specified
     * register is a destination register for this instruction, or if the pre-instruction register type didn't change
     * after merging in the given register type
     */
    protected boolean mergeRegister(int registerNumber, RegisterType registerType, BitSet verifiedInstructions) {
        assert registerNumber >= 0 && registerNumber < postRegisterMap.length;
        assert registerType != null;

        RegisterType oldRegisterType = preRegisterMap[registerNumber];
        RegisterType mergedRegisterType = oldRegisterType.merge(registerType);

        if (mergedRegisterType == oldRegisterType) {
            return false;
        }

        preRegisterMap[registerNumber] = mergedRegisterType;
        verifiedInstructions.clear(instructionIndex);

        if (!setsRegister(registerNumber)) {
            postRegisterMap[registerNumber] = mergedRegisterType;
            return true;
        }

        return false;
    }

    /**
     * Iterates over the predecessors of this instruction, and merges all the post-instruction register types for the
     * given register. Any dead, unreachable, or odexed predecessor is ignored
     * @param registerNumber the register number
     * @return The register type resulting from merging the post-instruction register types from all predecessors
     */
    protected RegisterType mergePreRegisterTypeFromPredecessors(int registerNumber) {
        RegisterType mergedRegisterType = null;
        for (AnalyzedInstruction predecessor: predecessors) {
            RegisterType predecessorRegisterType = predecessor.postRegisterMap[registerNumber];
            assert predecessorRegisterType != null;
            mergedRegisterType = predecessorRegisterType.merge(mergedRegisterType);
        }
        return mergedRegisterType;
    }

    /*
      * Sets the "post-instruction" register type as indicated.
      * @param registerNumber Which register to set
      * @param registerType The "post-instruction" register type
      * @returns true if the given register type is different than the existing post-instruction register type
      */
     protected boolean setPostRegisterType(int registerNumber, RegisterType registerType) {
         assert registerNumber >= 0 && registerNumber < postRegisterMap.length;
         assert registerType != null;

         RegisterType oldRegisterType = postRegisterMap[registerNumber];
         if (oldRegisterType == registerType) {
             return false;
         }

         postRegisterMap[registerNumber] = registerType;
         return true;
     }


    protected boolean isInvokeInit() {
        if (instruction == null ||
                (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE &&
                instruction.opcode != Opcode.INVOKE_DIRECT_EMPTY)) {
            return false;
        }

        //TODO: check access flags instead of name?

        InstructionWithReference instruction = (InstructionWithReference)this.instruction;
        Item item = instruction.getReferencedItem();
        assert item.getItemType() == ItemType.TYPE_METHOD_ID_ITEM;
        MethodIdItem method = (MethodIdItem)item;

        if (!method.getMethodName().getStringValue().equals("<init>")) {
            return false;
        }

        return true;
    }

    public boolean setsRegister() {
        return instruction.opcode.setsRegister();
    }

    public boolean setsWideRegister() {
        return instruction.opcode.setsWideRegister();
    }

    public boolean setsRegister(int registerNumber) {
        //When constructing a new object, the register type will be an uninitialized reference after the new-instance
        //instruction, but becomes an initialized reference once the <init> method is called. So even though invoke
        //instructions don't normally change any registers, calling an <init> method will change the type of its
        //object register. If the uninitialized reference has been copied to other registers, they will be initialized
        //as well, so we need to check for that too
        if (isInvokeInit()) {
            int destinationRegister;
            if (instruction instanceof FiveRegisterInstruction) {
                destinationRegister = ((FiveRegisterInstruction)instruction).getRegisterD();
            } else {
                assert instruction instanceof RegisterRangeInstruction;
                RegisterRangeInstruction rangeInstruction = (RegisterRangeInstruction)instruction;
                assert rangeInstruction.getRegCount() > 0;
                destinationRegister = rangeInstruction.getStartRegister();
            }

            if (registerNumber == destinationRegister) {
                return true;
            }
            RegisterType preInstructionDestRegisterType = getPreInstructionRegisterType(registerNumber);
            if (preInstructionDestRegisterType.category != RegisterType.Category.UninitRef &&
                preInstructionDestRegisterType.category != RegisterType.Category.UninitThis) {

                return false;
            }
            //check if the uninit ref has been copied to another register
            if (getPreInstructionRegisterType(registerNumber) == preInstructionDestRegisterType) {
                return true;
            }
            return false;
        }

        if (!setsRegister()) {
            return false;
        }
        int destinationRegister = getDestinationRegister();

        if (registerNumber == destinationRegister) {
            return true;
        }
        if (setsWideRegister() && registerNumber == (destinationRegister + 1)) {
            return true;
        }
        return false;
    }

    public int getDestinationRegister() {
        if (!this.instruction.opcode.setsRegister()) {
            throw new ExceptionWithContext("Cannot call getDestinationRegister() for an instruction that doesn't " +
                    "store a value");
        }
        return ((SingleRegisterInstruction)instruction).getRegisterA();
    }

    public int getRegisterCount() {
        return postRegisterMap.length;
    }

    public RegisterType getPostInstructionRegisterType(int registerNumber) {
        return postRegisterMap[registerNumber];
    }

    public RegisterType getPreInstructionRegisterType(int registerNumber) {
        return preRegisterMap[registerNumber];
    }

    public int compareTo(AnalyzedInstruction analyzedInstruction) {
        //TODO: out of curiosity, check the disassembly of this to see if it retrieves the value of analyzedInstruction.instructionIndex for every access. It should, because the field is final. What about if we set the field to non-final?
        if (instructionIndex < analyzedInstruction.instructionIndex) {
            return -1;
        } else if (instructionIndex == analyzedInstruction.instructionIndex) {
            return 0;
        } else {
            return 1;
        }
    }
}

