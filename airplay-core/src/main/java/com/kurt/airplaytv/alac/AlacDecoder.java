/*
 * Copyright (c) 2011 Apple Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file is a Java port of Apple's reference ALAC decoder
 * (originally published by Apple Inc. at macosforge/alac, Apache
 * License 2.0). It is a faithful translation of the C/C++ decode path:
 * ALACDecoder.cpp, ALACBitUtilities.c, ag_dec.c, dp_dec.c and
 * matrix_dec.c. The encoder is not ported.
 */

package com.kurt.airplaytv.alac;

/**
 * Pure-Java ALAC (Apple Lossless Audio Codec) decoder.
 *
 * <p>Decodes one ALAC frame at a time: the input is a raw element stream
 * exactly as it appears in one sample of an .m4a file or in one AirPlay
 * RAOP audio packet payload. The output is interleaved little-endian PCM
 * (16-bit samples when {@code bitDepth == 16}).</p>
 *
 * <p>The decoder is not thread-safe; use one instance per stream.</p>
 */
public final class AlacDecoder {

    // ---- status codes (ALACAudioTypes.h / ALACBitUtilities.h) ----
    private static final int ALAC_noErr = 0;
    private static final int kALAC_ParamError = -50;

    // ---- element types (ALACBitUtilities.h) ----
    private static final int ID_SCE = 0;    // Single Channel Element
    private static final int ID_CPE = 1;    // Channel Pair Element
    private static final int ID_CCE = 2;    // Coupling Channel Element
    private static final int ID_LFE = 3;    // LFE Channel Element
    private static final int ID_DSE = 4;    // Data Stream Element
    private static final int ID_PCE = 5;    // Program Config Element
    private static final int ID_FIL = 6;    // Fill Element
    private static final int ID_END = 7;    // End / frame terminator

    // ---- adaptive Golomb constants (aglib.h) ----
    private static final int QBSHIFT = 9;
    private static final int QB = (1 << QBSHIFT);
    private static final int MMULSHIFT = 2;
    private static final int MDENSHIFT = (QBSHIFT - MMULSHIFT - 1);
    private static final int MOFF = (1 << (MDENSHIFT - 2));
    private static final int BITOFF = 24;
    private static final int MAX_PREFIX_16 = 9;
    private static final int MAX_PREFIX_32 = 9;
    private static final int MAX_DATATYPE_BITS_16 = 16;
    private static final int N_MAX_MEAN_CLAMP = 0xffff;
    private static final int N_MEAN_CLAMP_VAL = 0xffff;
    private static final int MAX_RUN_DEFAULT = 255;

    /** Number of zero bytes appended to the working copy of each frame so the
     *  bit readers may look ahead a few bytes past the logical end of the
     *  frame, exactly as the C code reads past {@code cur} within its
     *  caller-provided buffer. */
    private static final int BITSTREAM_PAD = 16;

    // ---- configuration (ALACSpecificConfig) ----
    private final int frameLength;
    private final int bitDepth;
    private final int pb;
    private final int mb;
    private final int kb;
    private final int numChannels;
    private final int sampleRate;
    private final int maxRun = MAX_RUN_DEFAULT;

    // ---- decoding buffers ----
    private final int[] mMixBufferU;
    private final int[] mMixBufferV;
    private final int[] mPredictor;
    /** The C code overlays this uint16 buffer on mPredictor; the predictor is
     *  dead by the time shifted values are read, so a separate array of
     *  unsigned 16-bit values (stored in ints) is semantically identical. */
    private final int[] mShiftBuffer;

    /**
     * @param frameLength samples per frame (352 for AirPlay RAOP, often 4096 for files)
     * @param bitDepth    bits per sample: 16, 20, 24 or 32
     * @param pb          adaptive Golomb pb parameter (40 for RAOP)
     * @param mb          adaptive Golomb mb parameter (10 for RAOP)
     * @param kb          adaptive Golomb kb parameter (14 for RAOP)
     * @param numChannels channel count of the stream (1 or 2 for RAOP)
     * @param sampleRate  sample rate in Hz (44100 for RAOP); carried for
     *                    completeness, the codec itself does not use it
     */
    public AlacDecoder(int frameLength, int bitDepth, int pb, int mb, int kb,
                       int numChannels, int sampleRate) {
        if (frameLength <= 0) {
            throw new IllegalArgumentException("frameLength must be > 0");
        }
        if (numChannels <= 0) {
            throw new IllegalArgumentException("numChannels must be > 0");
        }
        this.frameLength = frameLength;
        this.bitDepth = bitDepth;
        this.pb = pb;
        this.mb = mb;
        this.kb = kb;
        this.numChannels = numChannels;
        this.sampleRate = sampleRate;

        mMixBufferU = new int[frameLength];
        mMixBufferV = new int[frameLength];
        mPredictor = new int[frameLength];
        mShiftBuffer = new int[frameLength * 2];
    }

    private int bytesPerSample() {
        switch (bitDepth) {
            case 16: return 2;
            case 20: return 3;
            case 24: return 3;
            case 32: return 4;
            default: return (bitDepth + 7) / 8;
        }
    }

    /**
     * Decode one ALAC frame (a raw element stream as sent over AirPlay RAOP,
     * i.e. exactly the bytes of one sample in an .m4a).
     *
     * <p>Writes interleaved little-endian PCM (16-bit samples when
     * bitDepth == 16) into {@code pcmOut}, starting at index 0.</p>
     *
     * @return the number of bytes written to {@code pcmOut}
     */
    public int decodeFrame(byte[] in, int inOffset, int inLength, byte[] pcmOut) {
        if (in == null || pcmOut == null) {
            throw new NullPointerException("in/pcmOut must not be null");
        }
        if (inOffset < 0 || inLength < 0 || inOffset + inLength > in.length) {
            throw new IndexOutOfBoundsException("bad in offset/length");
        }
        int bytesPerSample = bytesPerSample();
        if (pcmOut.length < frameLength * numChannels * bytesPerSample) {
            throw new IllegalArgumentException("pcmOut too small: need at least "
                    + (frameLength * numChannels * bytesPerSample) + " bytes");
        }

        // Working copy with zero padding so bit readers may read a few bytes
        // past the logical end without faulting (the C code does the same
        // within its caller-provided buffer).
        byte[] work = new byte[inLength + BITSTREAM_PAD];
        System.arraycopy(in, inOffset, work, 0, inLength);
        BitBuffer bits = new BitBuffer(work, inLength);

        // ---- translation of ALACDecoder::Decode() ----
        BitBuffer shiftBits = null;
        int tag;
        int elementInstanceTag;
        AgParams agParams = new AgParams();
        int channelIndex = 0;
        short[] coefsU = new short[32];
        short[] coefsV = new short[32];
        int numU, numV;
        int mixBits = 0;
        int mixRes = 0;
        int status = ALAC_noErr;

        int activeElements = 0;
        int numSamples = frameLength;          // requested samples (Decode's numSamples arg)
        int outNumSamples = numSamples;        // *outNumSamples is preset to numSamples

        boolean done = false;                  // left the element loop
        boolean fillRemaining = true;          // fell through to NoMoreChannels (zero-fill)

        while (status == ALAC_noErr && !done) {
            // bail if we ran off the end of the buffer (goto Exit: no zero-fill)
            if (!(bits.cur < bits.end)) {
                status = kALAC_ParamError;
                fillRemaining = false;
                break;
            }

            // copy global decode params for this element
            int pbLocal = pb;

            // read element tag
            tag = bits.readSmall(3);
            switch (tag) {
                case ID_SCE:
                case ID_LFE: {
                    // mono/LFE channel
                    elementInstanceTag = bits.readSmall(4);
                    activeElements |= (1 << elementInstanceTag);

                    // read the 12 unused header bits
                    int unusedHeader = bits.read(12);
                    if (unusedHeader != 0) {
                        status = kALAC_ParamError;
                        fillRemaining = false;
                        done = true;
                        break;
                    }

                    // read the 1-bit "partial frame" flag, 2-bit "shift-off" flag & 1-bit "escape" flag
                    int headerByte = bits.read(4);

                    int partialFrame = headerByte >> 3;

                    int bytesShifted = (headerByte >> 1) & 0x3;
                    if (bytesShifted == 3) {
                        status = kALAC_ParamError;
                        fillRemaining = false;
                        done = true;
                        break;
                    }

                    int shift = bytesShifted * 8;

                    int escapeFlag = headerByte & 0x1;

                    int chanBits = bitDepth - (bytesShifted * 8);

                    // check for partial frame to override requested numSamples
                    if (partialFrame != 0) {
                        numSamples = bits.read(16) << 16;
                        numSamples |= bits.read(16);
                        if (numSamples > frameLength) {
                            // safety guard (C would overflow its buffers on
                            // such a corrupt stream)
                            status = kALAC_ParamError;
                            fillRemaining = false;
                            done = true;
                            break;
                        }
                    }

                    if (escapeFlag == 0) {
                        // compressed frame, read rest of parameters
                        mixBits = bits.read(8);
                        mixRes = (byte) bits.read(8);
                        // no mixing for mono

                        headerByte = bits.read(8);
                        int modeU = headerByte >> 4;
                        int denShiftU = headerByte & 0xf;

                        headerByte = bits.read(8);
                        int pbFactorU = headerByte >> 5;
                        numU = headerByte & 0x1f;

                        for (int i = 0; i < numU; i++) {
                            coefsU[i] = (short) bits.read(16);
                        }

                        // if shift active, skip the shift buffer but remember where it starts
                        if (bytesShifted != 0) {
                            shiftBits = bits.copy();
                            bits.advance((bytesShifted * 8) * numSamples);
                        }

                        // decompress
                        setAgParams(agParams, mb, (pbLocal * pbFactorU) / 4, kb,
                                numSamples, numSamples, maxRun);
                        status = dynDecomp(agParams, bits, mPredictor, numSamples, chanBits);
                        if (status != ALAC_noErr) {
                            fillRemaining = false;
                            done = true;
                            break;
                        }

                        if (modeU == 0) {
                            unpcBlock(mPredictor, mMixBufferU, numSamples, coefsU, numU, chanBits, denShiftU);
                        } else {
                            // the special "numActive == 31" mode can be done in-place
                            unpcBlock(mPredictor, mPredictor, numSamples, null, 31, chanBits, 0);
                            unpcBlock(mPredictor, mMixBufferU, numSamples, coefsU, numU, chanBits, denShiftU);
                        }
                    } else {
                        // uncompressed frame, copy data into the mix buffer to use common output code
                        shift = 32 - chanBits;
                        if (chanBits <= 16) {
                            for (int i = 0; i < numSamples; i++) {
                                int val = bits.read(chanBits);
                                val = (val << shift) >> shift;
                                mMixBufferU[i] = val;
                            }
                        } else {
                            // BitBufferRead() can't read more than 16 bits at a time so break up the reads
                            int extraBits = chanBits - 16;
                            for (int i = 0; i < numSamples; i++) {
                                int val = bits.read(16);
                                val = (val << 16) >> shift;
                                mMixBufferU[i] = val | bits.read(extraBits);
                            }
                        }

                        mixBits = mixRes = 0;
                        bytesShifted = 0;
                    }

                    // now read the shifted values into the shift buffer
                    if (bytesShifted != 0) {
                        shift = bytesShifted * 8;

                        for (int i = 0; i < numSamples; i++) {
                            mShiftBuffer[i] = shiftBits.read(shift);
                        }
                    }

                    // convert 32-bit integers into output buffer
                    switch (bitDepth) {
                        case 16: {
                            int pos = channelIndex * 2;
                            for (int i = 0; i < numSamples; i++) {
                                put16(pcmOut, pos, mMixBufferU[i]);
                                pos += numChannels * 2;
                            }
                            break;
                        }
                        case 20: {
                            int pos = channelIndex * 3;
                            copyPredictorTo20(mMixBufferU, pcmOut, pos, numChannels, numSamples);
                            break;
                        }
                        case 24: {
                            int pos = channelIndex * 3;
                            if (bytesShifted != 0) {
                                copyPredictorTo24Shift(mMixBufferU, mShiftBuffer, pcmOut, pos,
                                        numChannels, numSamples, bytesShifted);
                            } else {
                                copyPredictorTo24(mMixBufferU, pcmOut, pos, numChannels, numSamples);
                            }
                            break;
                        }
                        case 32: {
                            int pos = channelIndex * 4;
                            if (bytesShifted != 0) {
                                copyPredictorTo32Shift(mMixBufferU, mShiftBuffer, pcmOut, pos,
                                        numChannels, numSamples, bytesShifted);
                            } else {
                                copyPredictorTo32(mMixBufferU, pcmOut, pos, numChannels, numSamples);
                            }
                            break;
                        }
                    }

                    channelIndex += 1;
                    outNumSamples = numSamples;
                    break;
                }

                case ID_CPE: {
                    // if decoding this pair would take us over the max channels limit, bail
                    // (goto NoMoreChannels: zero-fill still happens)
                    if ((channelIndex + 2) > numChannels) {
                        done = true;
                        break;
                    }

                    // stereo channel pair
                    elementInstanceTag = bits.readSmall(4);
                    activeElements |= (1 << elementInstanceTag);

                    // read the 12 unused header bits
                    int unusedHeader = bits.read(12);
                    if (unusedHeader != 0) {
                        status = kALAC_ParamError;
                        fillRemaining = false;
                        done = true;
                        break;
                    }

                    // read the 1-bit "partial frame" flag, 2-bit "shift-off" flag & 1-bit "escape" flag
                    int headerByte = bits.read(4);

                    int partialFrame = headerByte >> 3;

                    int bytesShifted = (headerByte >> 1) & 0x3;
                    if (bytesShifted == 3) {
                        status = kALAC_ParamError;
                        fillRemaining = false;
                        done = true;
                        break;
                    }

                    int shift = bytesShifted * 8;

                    int escapeFlag = headerByte & 0x1;

                    int chanBits = bitDepth - (bytesShifted * 8) + 1;

                    // check for partial frame length to override requested numSamples
                    if (partialFrame != 0) {
                        numSamples = bits.read(16) << 16;
                        numSamples |= bits.read(16);
                        if (numSamples > frameLength) {
                            // safety guard (C would overflow its buffers)
                            status = kALAC_ParamError;
                            fillRemaining = false;
                            done = true;
                            break;
                        }
                    }

                    if (escapeFlag == 0) {
                        // compressed frame, read rest of parameters
                        mixBits = bits.read(8);
                        mixRes = (byte) bits.read(8);

                        headerByte = bits.read(8);
                        int modeU = headerByte >> 4;
                        int denShiftU = headerByte & 0xf;

                        headerByte = bits.read(8);
                        int pbFactorU = headerByte >> 5;
                        numU = headerByte & 0x1f;
                        for (int i = 0; i < numU; i++) {
                            coefsU[i] = (short) bits.read(16);
                        }

                        headerByte = bits.read(8);
                        int modeV = headerByte >> 4;
                        int denShiftV = headerByte & 0xf;

                        headerByte = bits.read(8);
                        int pbFactorV = headerByte >> 5;
                        numV = headerByte & 0x1f;
                        for (int i = 0; i < numV; i++) {
                            coefsV[i] = (short) bits.read(16);
                        }

                        // if shift active, skip the interleaved shifted values but remember where they start
                        if (bytesShifted != 0) {
                            shiftBits = bits.copy();
                            bits.advance((bytesShifted * 8) * 2 * numSamples);
                        }

                        // decompress and run predictor for "left" channel
                        setAgParams(agParams, mb, (pbLocal * pbFactorU) / 4, kb,
                                numSamples, numSamples, maxRun);
                        status = dynDecomp(agParams, bits, mPredictor, numSamples, chanBits);
                        if (status != ALAC_noErr) {
                            fillRemaining = false;
                            done = true;
                            break;
                        }

                        if (modeU == 0) {
                            unpcBlock(mPredictor, mMixBufferU, numSamples, coefsU, numU, chanBits, denShiftU);
                        } else {
                            // the special "numActive == 31" mode can be done in-place
                            unpcBlock(mPredictor, mPredictor, numSamples, null, 31, chanBits, 0);
                            unpcBlock(mPredictor, mMixBufferU, numSamples, coefsU, numU, chanBits, denShiftU);
                        }

                        // decompress and run predictor for "right" channel
                        setAgParams(agParams, mb, (pbLocal * pbFactorV) / 4, kb,
                                numSamples, numSamples, maxRun);
                        status = dynDecomp(agParams, bits, mPredictor, numSamples, chanBits);
                        if (status != ALAC_noErr) {
                            fillRemaining = false;
                            done = true;
                            break;
                        }

                        if (modeV == 0) {
                            unpcBlock(mPredictor, mMixBufferV, numSamples, coefsV, numV, chanBits, denShiftV);
                        } else {
                            // the special "numActive == 31" mode can be done in-place
                            unpcBlock(mPredictor, mPredictor, numSamples, null, 31, chanBits, 0);
                            unpcBlock(mPredictor, mMixBufferV, numSamples, coefsV, numV, chanBits, denShiftV);
                        }
                    } else {
                        // uncompressed frame, copy data into the mix buffers to use common output code
                        chanBits = bitDepth;
                        shift = 32 - chanBits;
                        if (chanBits <= 16) {
                            for (int i = 0; i < numSamples; i++) {
                                int val = bits.read(chanBits);
                                val = (val << shift) >> shift;
                                mMixBufferU[i] = val;

                                val = bits.read(chanBits);
                                val = (val << shift) >> shift;
                                mMixBufferV[i] = val;
                            }
                        } else {
                            // BitBufferRead() can't read more than 16 bits at a time so break up the reads
                            int extraBits = chanBits - 16;
                            for (int i = 0; i < numSamples; i++) {
                                int val = bits.read(16);
                                val = (val << 16) >> shift;
                                mMixBufferU[i] = val | bits.read(extraBits);

                                val = bits.read(16);
                                val = (val << 16) >> shift;
                                mMixBufferV[i] = val | bits.read(extraBits);
                            }
                        }

                        mixBits = mixRes = 0;
                        bytesShifted = 0;
                    }

                    // now read the shifted values into the shift buffer
                    if (bytesShifted != 0) {
                        shift = bytesShifted * 8;

                        for (int i = 0; i < (numSamples * 2); i += 2) {
                            mShiftBuffer[i + 0] = shiftBits.read(shift);
                            mShiftBuffer[i + 1] = shiftBits.read(shift);
                        }
                    }

                    // un-mix the data and convert to output format
                    // - note that mixRes = 0 means just interleave so we use that path for uncompressed frames
                    switch (bitDepth) {
                        case 16: {
                            int pos = channelIndex * 2;
                            unmix16(mMixBufferU, mMixBufferV, pcmOut, pos, numChannels,
                                    numSamples, mixBits, mixRes);
                            break;
                        }
                        case 20: {
                            int pos = channelIndex * 3;
                            unmix20(mMixBufferU, mMixBufferV, pcmOut, pos, numChannels,
                                    numSamples, mixBits, mixRes);
                            break;
                        }
                        case 24: {
                            int pos = channelIndex * 3;
                            unmix24(mMixBufferU, mMixBufferV, pcmOut, pos, numChannels,
                                    numSamples, mixBits, mixRes, mShiftBuffer, bytesShifted);
                            break;
                        }
                        case 32: {
                            int pos = channelIndex * 4;
                            unmix32(mMixBufferU, mMixBufferV, pcmOut, pos, numChannels,
                                    numSamples, mixBits, mixRes, mShiftBuffer, bytesShifted);
                            break;
                        }
                    }

                    channelIndex += 2;
                    outNumSamples = numSamples;
                    break;
                }

                case ID_CCE:
                case ID_PCE: {
                    // unsupported element, bail
                    status = kALAC_ParamError;
                    break;
                }

                case ID_DSE: {
                    // data stream element -- parse but ignore
                    status = dataStreamElement(bits);
                    break;
                }

                case ID_FIL: {
                    // fill element -- parse but ignore
                    status = fillElement(bits);
                    break;
                }

                case ID_END: {
                    // frame end, all done so byte align the frame (goto Exit: no zero-fill)
                    bits.byteAlign();
                    fillRemaining = false;
                    done = true;
                    break;
                }
            }

            // if we've decoded all of our channels, bail
            if (!done && channelIndex >= numChannels) {
                break;
            }
        }

        // NoMoreChannels: if we get here (without an Exit jump) and haven't
        // decoded all of the requested channels, fill the rest with zeros.
        // - the C switch has no 20-bit case, preserved here.
        if (fillRemaining) {
            for (; channelIndex < numChannels; channelIndex++) {
                switch (bitDepth) {
                    case 16: {
                        int pos = channelIndex * 2;
                        for (int i = 0; i < numSamples; i++) {
                            pcmOut[pos] = 0;
                            pcmOut[pos + 1] = 0;
                            pos += numChannels * 2;
                        }
                        break;
                    }
                    case 24: {
                        int pos = channelIndex * 3;
                        for (int i = 0; i < numSamples; i++) {
                            pcmOut[pos] = 0;
                            pcmOut[pos + 1] = 0;
                            pcmOut[pos + 2] = 0;
                            pos += numChannels * 3;
                        }
                        break;
                    }
                    case 32: {
                        int pos = channelIndex * 4;
                        for (int i = 0; i < numSamples; i++) {
                            pcmOut[pos] = 0;
                            pcmOut[pos + 1] = 0;
                            pcmOut[pos + 2] = 0;
                            pcmOut[pos + 3] = 0;
                            pos += numChannels * 4;
                        }
                        break;
                    }
                }
            }
        }

        return outNumSamples * numChannels * bytesPerSample;
    }

    /*
     * FillElement() -- they're just filler so we don't need 'em
     */
    private static int fillElement(BitBuffer bits) {
        int count;

        // 4-bit count or (4-bit + 8-bit count) if 4-bit count == 15
        // - plus this weird -1 thing I still don't fully understand
        count = bits.readSmall(4);
        if (count == 15) {
            count += bits.readSmall(8) - 1;
        }

        bits.advance(count * 8);

        if (!(bits.cur <= bits.end)) {
            return kALAC_ParamError;
        }

        return ALAC_noErr;
    }

    /*
     * DataStreamElement() -- we don't care about data stream elements so just skip them
     */
    private static int dataStreamElement(BitBuffer bits) {
        // the tag associates this data stream element with a given audio element
        bits.readSmall(4);

        int dataByteAlignFlag = bits.readOne();

        // 8-bit count or (8-bit + 8-bit count) if 8-bit count == 255
        int count = bits.readSmall(8);
        if (count == 255) {
            count += bits.readSmall(8);
        }

        // the align flag means the bitstream should be byte-aligned before reading the following data bytes
        if (dataByteAlignFlag != 0) {
            bits.byteAlign();
        }

        // skip the data bytes
        bits.advance(count * 8);

        if (!(bits.cur <= bits.end)) {
            return kALAC_ParamError;
        }

        return ALAC_noErr;
    }


    // ================================================================
    // BitBuffer (ALACBitUtilities.c)
    // ================================================================

    /**
     * Bit-level reader. {@code cur} is an index into {@code buf}; bits are
     * read MSB-first. {@code end} is the logical end of the frame (the C
     * {@code end} pointer); {@code buf} may extend further with zero padding
     * so multi-byte lookahead reads never fault.
     */
    private static final class BitBuffer {
        final byte[] buf;
        int cur;
        final int end;
        int bitIndex;
        final int byteSize;

        BitBuffer(byte[] buf, int byteSize) {
            this.buf = buf;
            this.byteSize = byteSize;
            this.cur = 0;
            this.end = byteSize;
            this.bitIndex = 0;
        }

        BitBuffer copy() {
            BitBuffer b = new BitBuffer(buf, byteSize);
            b.cur = cur;
            b.bitIndex = bitIndex;
            return b;
        }

        /** BitBufferRead(): note: cannot read more than 16 bits at a time. */
        int read(int numBits) {
            int returnBits = ((buf[cur] & 0xff) << 16)
                    | ((buf[cur + 1] & 0xff) << 8)
                    | (buf[cur + 2] & 0xff);
            returnBits = returnBits << bitIndex;
            returnBits &= 0x00FFFFFF;

            bitIndex += numBits;

            returnBits = returnBits >>> (24 - numBits);

            cur += (bitIndex >> 3);
            bitIndex &= 7;

            return returnBits;
        }

        /** BitBufferReadSmall(): reads up to 8 bits. */
        int readSmall(int numBits) {
            // the uint16_t truncation after the left shift is load-bearing:
            // it discards bits shifted past bit 15 before the right shift
            int returnBits = ((buf[cur] & 0xff) << 8) | (buf[cur + 1] & 0xff);
            returnBits = (returnBits << bitIndex) & 0xffff;

            bitIndex += numBits;

            returnBits = returnBits >> (16 - numBits);

            cur += (bitIndex >> 3);
            bitIndex &= 7;

            return returnBits;
        }

        /** BitBufferReadOne(): reads one bit. */
        int readOne() {
            int returnBits = ((buf[cur] & 0xff) >> (7 - bitIndex)) & 1;

            bitIndex++;

            cur += (bitIndex >> 3);
            bitIndex &= 7;

            return returnBits;
        }

        /** BitBufferByteAlign(false): align to the next byte boundary. */
        void byteAlign() {
            if (bitIndex == 0) {
                return;
            }
            advance(8 - bitIndex);
        }

        /** BitBufferAdvance(). */
        void advance(int numBits) {
            if (numBits != 0) {
                bitIndex += numBits;
                cur += (bitIndex >> 3);
                bitIndex &= 7;
            }
        }
    }

    // ================================================================
    // Adaptive Golomb entropy decoder (ag_dec.c)
    // ================================================================

    /** AGParamRec (aglib.h). Only mb0, pb, kb, wb are used by the decode path. */
    private static final class AgParams {
        int mb, mb0, pb, kb, wb, qb;
        int fw, sw;
        int maxrun;
    }

    private static void setAgParams(AgParams params, int m, int p, int k,
                                    int f, int s, int maxrun) {
        params.mb = params.mb0 = m;
        params.pb = p;
        params.kb = k;
        params.wb = (1 << params.kb) - 1;
        params.qb = QB - params.pb;
        params.fw = f;
        params.sw = s;
        params.maxrun = maxrun;
    }

    /** lead(): count leading zero bits of a 32-bit pattern. */
    private static int lead(int m) {
        return Integer.numberOfLeadingZeros(m);
    }

    private static int lg3a(int x) {
        x += 3;
        int result = lead(x);
        return 31 - result;
    }

    /** read32bit(): big-endian read of 4 bytes (caller's buffer is padded). */
    private static int read32bit(byte[] in, int off) {
        return ((in[off] & 0xff) << 24)
                | ((in[off + 1] & 0xff) << 16)
                | ((in[off + 2] & 0xff) << 8)
                | (in[off + 3] & 0xff);
    }

    /** get_next_fromlong(): top 'suff' bits of a 32-bit word. */
    private static int getNextFromLong(int inlong, int suff) {
        // the C macro is ((uint32_t) >> (32 - suff)); suff > 0 in practice
        if (suff <= 0) {
            return 0;
        }
        return inlong >>> (32 - suff);
    }

    private static int getStreamBits(byte[] in, int base, int bitoffset, int numbits) {
        int byteoffset = bitoffset / 8;
        int result;

        int load1 = read32bit(in, base + byteoffset);

        if ((numbits + (bitoffset & 0x7)) > 32) {
            int load2shift;

            result = load1 << (bitoffset & 0x7);
            int load2 = in[base + byteoffset + 4] & 0xff;
            load2shift = (8 - (numbits + (bitoffset & 0x7) - 32));
            load2 >>>= load2shift;
            result >>>= (32 - numbits);
            result |= load2;
        } else {
            result = load1 >>> (32 - numbits - (bitoffset & 7));
        }

        // a shift of >= "the number of bits in the type of the value being
        // shifted" results in undefined behavior so don't try to shift by 32
        if (numbits != 32) {
            result &= (numbits == 0) ? 0 : ((1 << numbits) - 1);
        }

        return result;
    }

    private static int dynGet(byte[] in, int base, int[] bitPos, int m, int k) {
        int tempbits = bitPos[0];
        int result;
        int pre, v;
        int streamlong;

        streamlong = read32bit(in, base + (tempbits >> 3));
        streamlong <<= (tempbits & 7);

        /* find the number of bits in the prefix */
        pre = lead(~streamlong);

        if (pre >= MAX_PREFIX_16) {
            pre = MAX_PREFIX_16;
            tempbits += pre;
            streamlong <<= pre;
            result = getNextFromLong(streamlong, MAX_DATATYPE_BITS_16);
            tempbits += MAX_DATATYPE_BITS_16;
        } else {
            // all of the bits must fit within the long we have loaded
            tempbits += pre;
            tempbits += 1;
            streamlong <<= pre + 1;
            v = getNextFromLong(streamlong, k);
            tempbits += k;

            result = pre * m + v - 1;

            if (v < 2) {
                result -= (v - 1);
                tempbits -= 1;
            }
        }

        bitPos[0] = tempbits;
        return result;
    }

    private static int dynGet32bit(byte[] in, int base, int[] bitPos, int m, int k, int maxbits) {
        int tempbits = bitPos[0];
        int v;
        int streamlong;
        int result;

        streamlong = read32bit(in, base + (tempbits >> 3));
        streamlong <<= (tempbits & 7);

        /* find the number of bits in the prefix */
        result = lead(~streamlong);

        if (result >= MAX_PREFIX_32) {
            result = getStreamBits(in, base, tempbits + MAX_PREFIX_32, maxbits);
            tempbits += MAX_PREFIX_32 + maxbits;
        } else {
            /* all of the bits must fit within the long we have loaded */
            tempbits += result;
            tempbits += 1;

            if (k != 1) {
                streamlong <<= result + 1;
                v = getNextFromLong(streamlong, k);
                tempbits += k;
                tempbits -= 1;
                result = result * m;

                if (v >= 2) {
                    result += (v - 1);
                    tempbits += 1;
                }
            }
        }

        bitPos[0] = tempbits;
        return result;
    }

    /**
     * dyn_decomp(): adaptive Golomb decode of {@code numSamples} residuals
     * into {@code pc}, advancing {@code bitstream} past the consumed bits.
     */
    private static int dynDecomp(AgParams params, BitBuffer bitstream, int[] pc,
                                 int numSamples, int maxSize) {
        byte[] in = bitstream.buf;
        int base = bitstream.cur;
        int startPos = bitstream.bitIndex;
        int maxPos = bitstream.byteSize * 8;
        int[] bitPos = {startPos};
        int j, m, k, n, c, mz;
        int del, zmode;
        int mb;
        int pbLocal = params.pb;
        int kbLocal = params.kb;
        int wbLocal = params.wb;
        int status;
        int outIdx = 0;

        mb = params.mb0;
        zmode = 0;

        c = 0;
        status = ALAC_noErr;

        while (c < numSamples) {
            // bail if we've run off the end of the buffer
            if (!(bitPos[0] < maxPos)) {
                status = kALAC_ParamError;
                break;
            }

            m = (mb) >> QBSHIFT;
            k = lg3a(m);

            k = Math.min(k, kbLocal);
            m = (1 << k) - 1;

            n = dynGet32bit(in, base, bitPos, m, k, maxSize);

            // least significant bit is sign bit
            {
                int ndecode = n + zmode;
                int multiplier = (-(ndecode & 1));

                multiplier |= 1;
                del = ((ndecode + 1) >> 1) * (multiplier);
            }

            pc[outIdx++] = del;

            c++;

            mb = pbLocal * (n + zmode) + mb - ((pbLocal * mb) >> QBSHIFT);

            // update mean tracking
            if (n > N_MAX_MEAN_CLAMP) {
                mb = N_MEAN_CLAMP_VAL;
            }

            zmode = 0;

            if (((mb << MMULSHIFT) < QB) && (c < numSamples)) {
                zmode = 1;
                k = lead(mb) - BITOFF + ((mb + MOFF) >> MDENSHIFT);
                mz = ((1 << k) - 1) & wbLocal;

                n = dynGet(in, base, bitPos, mz, k);

                if (!(c + n <= numSamples)) {
                    status = kALAC_ParamError;
                    break;
                }

                for (j = 0; j < n; j++) {
                    pc[outIdx++] = 0;
                    ++c;
                }

                if (n >= 65535) {
                    zmode = 0;
                }

                mb = 0;
            }
        }

        int outNumBits = (bitPos[0] - startPos);
        bitstream.advance(outNumBits);
        if (!(bitstream.cur <= bitstream.end)) {
            status = kALAC_ParamError;
        }

        return status;
    }


    // ================================================================
    // Dynamic predictor (dp_dec.c)
    // ================================================================

    private static int signOfInt(int i) {
        int negishift;

        negishift = (-i) >>> 31;
        return negishift | (i >> 31);
    }

    /**
     * unpc_block(): reconstruct samples from residuals {@code pc} into
     * {@code out}. {@code pc} and {@code out} may be the same array.
     *
     * <p>The C code carries hand-optimized register variants for
     * numactive == 4 and numactive == 8; they are arithmetically identical
     * to the general case ported here, so only the general case (plus the
     * numactive == 0 and numactive == 31 short-circuits) is implemented.</p>
     */
    private static void unpcBlock(int[] pc, int[] out, int num, short[] coefs,
                                  int numactive, int chanbits, int denshift) {
        int j, k, lim;
        int sum1, sg, sgn, top, dd;
        int del, del0;
        int chanshift = 32 - chanbits;
        int denhalf = 1 << (denshift - 1);

        out[0] = pc[0];
        if (numactive == 0) {
            // just copy if numactive == 0 (but don't bother if in/out pointers the same)
            if ((num > 1) && (pc != out)) {
                System.arraycopy(pc, 1, out, 1, num - 1);
            }
            return;
        }
        if (numactive == 31) {
            // short-circuit if numactive == 31
            int prev;

            prev = out[0];
            for (j = 1; j < num; j++) {
                del = pc[j] + prev;
                prev = (del << chanshift) >> chanshift;
                out[j] = prev;
            }
            return;
        }

        for (j = 1; j <= numactive; j++) {
            del = pc[j] + out[j - 1];
            out[j] = (del << chanshift) >> chanshift;
        }

        lim = numactive + 1;

        // general case
        for (j = lim; j < num; j++) {
            sum1 = 0;
            top = out[j - lim];

            for (k = 0; k < numactive; k++) {
                sum1 += coefs[k] * (out[j - 1 - k] - top);
            }

            del = pc[j];
            del0 = del;
            sg = signOfInt(del);
            del += top + ((sum1 + denhalf) >> denshift);
            out[j] = (del << chanshift) >> chanshift;

            if (sg > 0) {
                for (k = (numactive - 1); k >= 0; k--) {
                    dd = top - out[j - 1 - k];
                    sgn = signOfInt(dd);
                    coefs[k] = (short) (coefs[k] - sgn);
                    del0 -= (numactive - k) * ((sgn * dd) >> denshift);
                    if (del0 <= 0) {
                        break;
                    }
                }
            } else if (sg < 0) {
                for (k = (numactive - 1); k >= 0; k--) {
                    dd = top - out[j - 1 - k];
                    sgn = signOfInt(dd);
                    coefs[k] = (short) (coefs[k] + sgn);
                    del0 -= (numactive - k) * ((-sgn * dd) >> denshift);
                    if (del0 >= 0) {
                        break;
                    }
                }
            }
        }
    }

    // ================================================================
    // Matrixing / output conversion (matrix_dec.c)
    //
    // Output is written into a byte array as little-endian PCM, matching
    // both the C code running on a little-endian host (LBYTE=0, MBYTE=1,
    // HBYTE=2) and this decoder's output contract.
    // ================================================================

    private static void put16(byte[] out, int pos, int val) {
        out[pos] = (byte) (val & 0xff);
        out[pos + 1] = (byte) ((val >> 8) & 0xff);
    }

    private static void put24(byte[] out, int pos, int val) {
        out[pos] = (byte) (val & 0xff);          // LBYTE
        out[pos + 1] = (byte) ((val >> 8) & 0xff);   // MBYTE
        out[pos + 2] = (byte) ((val >> 16) & 0xff);  // HBYTE
    }

    private static void put32(byte[] out, int pos, int val) {
        out[pos] = (byte) (val & 0xff);
        out[pos + 1] = (byte) ((val >> 8) & 0xff);
        out[pos + 2] = (byte) ((val >> 16) & 0xff);
        out[pos + 3] = (byte) ((val >> 24) & 0xff);
    }

    /** unmix16(): {@code outPos} is the byte offset of the first output
     *  channel; {@code stride} is the total channel count (in samples). */
    private static void unmix16(int[] u, int[] v, byte[] out, int outPos, int stride,
                                int numSamples, int mixbits, int mixres) {
        int pos = outPos;
        int j;

        if (mixres != 0) {
            /* matrixed stereo */
            for (j = 0; j < numSamples; j++) {
                int l, r;

                l = u[j] + v[j] - ((mixres * v[j]) >> mixbits);
                r = l - v[j];

                put16(out, pos, l);
                put16(out, pos + 2, r);
                pos += stride * 2;
            }
        } else {
            /* Conventional separated stereo. */
            for (j = 0; j < numSamples; j++) {
                put16(out, pos, u[j]);
                put16(out, pos + 2, v[j]);
                pos += stride * 2;
            }
        }
    }

    /** unmix20(): 20 bits of data are left-justified in 3 bytes of storage. */
    private static void unmix20(int[] u, int[] v, byte[] out, int outPos, int stride,
                                int numSamples, int mixbits, int mixres) {
        int pos = outPos;
        int j;

        if (mixres != 0) {
            /* matrixed stereo */
            for (j = 0; j < numSamples; j++) {
                int l, r;

                l = u[j] + v[j] - ((mixres * v[j]) >> mixbits);
                r = l - v[j];

                l <<= 4;
                r <<= 4;

                put24(out, pos, l);
                pos += 3;

                put24(out, pos, r);
                pos += (stride - 1) * 3;
            }
        } else {
            /* Conventional separated stereo. */
            for (j = 0; j < numSamples; j++) {
                int val;

                val = u[j] << 4;
                put24(out, pos, val);
                pos += 3;

                val = v[j] << 4;
                put24(out, pos, val);
                pos += (stride - 1) * 3;
            }
        }
    }

    /** unmix24(): 24 bits of data are right-justified in the predictor buffers. */
    private static void unmix24(int[] u, int[] v, byte[] out, int outPos, int stride,
                                int numSamples, int mixbits, int mixres,
                                int[] shiftUV, int bytesShifted) {
        int pos = outPos;
        int shift = bytesShifted * 8;
        int j, k;

        if (mixres != 0) {
            /* matrixed stereo */
            if (bytesShifted != 0) {
                for (j = 0, k = 0; j < numSamples; j++, k += 2) {
                    int l = u[j] + v[j] - ((mixres * v[j]) >> mixbits);
                    int r = l - v[j];

                    l = (l << shift) | shiftUV[k + 0];
                    r = (r << shift) | shiftUV[k + 1];

                    put24(out, pos, l);
                    pos += 3;

                    put24(out, pos, r);
                    pos += (stride - 1) * 3;
                }
            } else {
                for (j = 0; j < numSamples; j++) {
                    int l = u[j] + v[j] - ((mixres * v[j]) >> mixbits);
                    int r = l - v[j];

                    put24(out, pos, l);
                    pos += 3;

                    put24(out, pos, r);
                    pos += (stride - 1) * 3;
                }
            }
        } else {
            /* Conventional separated stereo. */
            if (bytesShifted != 0) {
                for (j = 0, k = 0; j < numSamples; j++, k += 2) {
                    int l = u[j];
                    int r = v[j];

                    l = (l << shift) | shiftUV[k + 0];
                    r = (r << shift) | shiftUV[k + 1];

                    put24(out, pos, l);
                    pos += 3;

                    put24(out, pos, r);
                    pos += (stride - 1) * 3;
                }
            } else {
                for (j = 0; j < numSamples; j++) {
                    put24(out, pos, u[j]);
                    pos += 3;

                    put24(out, pos, v[j]);
                    pos += (stride - 1) * 3;
                }
            }
        }
    }

    /** unmix32() */
    private static void unmix32(int[] u, int[] v, byte[] out, int outPos, int stride,
                                int numSamples, int mixbits, int mixres,
                                int[] shiftUV, int bytesShifted) {
        int pos = outPos;
        int shift = bytesShifted * 8;
        int j, k;

        if (mixres != 0) {
            /* matrixed stereo with shift */
            for (j = 0, k = 0; j < numSamples; j++, k += 2) {
                int lt, rt;

                lt = u[j];
                rt = v[j];

                int l = lt + rt - ((mixres * rt) >> mixbits);
                int r = l - rt;

                put32(out, pos, (l << shift) | shiftUV[k + 0]);
                put32(out, pos + 4, (r << shift) | shiftUV[k + 1]);
                pos += stride * 4;
            }
        } else {
            if (bytesShifted == 0) {
                /* interleaving w/o shift */
                for (j = 0; j < numSamples; j++) {
                    put32(out, pos, u[j]);
                    put32(out, pos + 4, v[j]);
                    pos += stride * 4;
                }
            } else {
                /* interleaving with shift */
                for (j = 0, k = 0; j < numSamples; j++, k += 2) {
                    put32(out, pos, (u[j] << shift) | shiftUV[k + 0]);
                    put32(out, pos + 4, (v[j] << shift) | shiftUV[k + 1]);
                    pos += stride * 4;
                }
            }
        }
    }

    /** copyPredictorTo24() */
    private static void copyPredictorTo24(int[] in, byte[] out, int outPos, int stride,
                                          int numSamples) {
        int pos = outPos;
        for (int j = 0; j < numSamples; j++) {
            put24(out, pos, in[j]);
            pos += (stride * 3);
        }
    }

    /** copyPredictorTo24Shift() */
    private static void copyPredictorTo24Shift(int[] in, int[] shift, byte[] out, int outPos,
                                               int stride, int numSamples, int bytesShifted) {
        int pos = outPos;
        int shiftVal = bytesShifted * 8;

        for (int j = 0; j < numSamples; j++) {
            int val = in[j];

            val = (val << shiftVal) | shift[j];

            put24(out, pos, val);
            pos += (stride * 3);
        }
    }

    /** copyPredictorTo20() */
    private static void copyPredictorTo20(int[] in, byte[] out, int outPos, int stride,
                                          int numSamples) {
        int pos = outPos;

        // 32-bit predictor values are right-aligned but 20-bit output values
        // should be left-aligned in the 24-bit output buffer
        for (int j = 0; j < numSamples; j++) {
            int val = in[j];

            out[pos + 2] = (byte) ((val >> 12) & 0xff);  // HBYTE
            out[pos + 1] = (byte) ((val >> 4) & 0xff);   // MBYTE
            out[pos + 0] = (byte) ((val << 4) & 0xff);   // LBYTE
            pos += (stride * 3);
        }
    }

    /** copyPredictorTo32() */
    private static void copyPredictorTo32(int[] in, byte[] out, int outPos, int stride,
                                          int numSamples) {
        int pos = outPos;
        for (int j = 0; j < numSamples; j++) {
            put32(out, pos, in[j]);
            pos += (stride * 4);
        }
    }

    /** copyPredictorTo32Shift() */
    private static void copyPredictorTo32Shift(int[] in, int[] shift, byte[] out, int outPos,
                                               int stride, int numSamples, int bytesShifted) {
        int pos = outPos;
        int shiftVal = bytesShifted * 8;

        for (int j = 0; j < numSamples; j++) {
            put32(out, pos, (in[j] << shiftVal) | shift[j]);
            pos += stride * 4;
        }
    }
}
